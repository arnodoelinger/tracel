package com.tracel.plugin.scheduler

import com.tracel.model.holder.HolderId
import com.tracel.platform.scheduler.TracelSchedulers
import kotlinx.coroutines.CoroutineDispatcher
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * [TracelSchedulers] backed by `Paper`'s `Folia`-safe region / entity / global / async schedulers.
 *
 * Each dispatcher only ever hands the scheduler API a plain `Runnable` — the resumption of
 * whatever coroutine called `withContext(...)` — and never blocks the thread it dispatches
 * onto waiting for that resumption to finish.
 */
class PaperTracelSchedulers(
    private val plugin: Plugin,
    override val storage: CoroutineDispatcher,
) : TracelSchedulers {
    override fun region(location: HolderId.Block): CoroutineDispatcher = RegionDispatcher(plugin, location)

    override fun entity(entity: UUID): CoroutineDispatcher = EntityDispatcher(plugin, entity)

    override val global: CoroutineDispatcher = GlobalDispatcher(plugin)

    override val async: CoroutineDispatcher = AsyncDispatcher(plugin)
}

private class RegionDispatcher(private val plugin: Plugin, private val holder: HolderId.Block) : CoroutineDispatcher() {
    /**
     * Resolves the world at dispatch time and falls back to the global region when it's gone,
     * rather than throwing while the dispatcher is merely being constructed — same shape
     * [EntityDispatcher] uses for an entity that no longer exists.
     *
     * `withContext(schedulers.region(holder))` evaluates the factory before the guarded block ever
     * runs, so throwing there turned an unloaded world into an unhandled coroutine exception
     * instead of the block's own "world is not loaded" check ever getting to run.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val world = Bukkit.getWorld(holder.world.uuid)
        if (world == null) {
            Bukkit.getGlobalRegionScheduler().execute(plugin) { block.run() }
            return
        }
        val location = Location(world, holder.x.toDouble(), holder.y.toDouble(), holder.z.toDouble())
        Bukkit.getRegionScheduler().execute(plugin, location) { block.run() }
    }
}

private class EntityDispatcher(private val plugin: Plugin, private val entityId: UUID) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // Bukkit.getEntity(UUID) itself touches chunk / entity state, so it may only be called
        // from a thread that already owns that state, never straight off dispatch()'s own caller.
        // Bounce onto the global region first, same as the entity-not-found fallback below assumes
        // is a safe place to run arbitrary handoff work.
        Bukkit.getGlobalRegionScheduler().execute(plugin) {
            val entity = Bukkit.getEntity(entityId)
            if (entity == null) {
                block.run()
            } else {
                entity.scheduler.execute(plugin, { block.run() }, { block.run() }, 0L)
            }
        }
    }
}

private class GlobalDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin) { block.run() }
    }
}

private class AsyncDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin) { block.run() }
    }
}
