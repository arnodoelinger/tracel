package com.tracel.plugin.scheduler

import com.tracel.model.holder.HolderId
import com.tracel.platform.scheduler.TracelSchedulers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext

/**
 * [TracelSchedulers] backed by `Paper`'s `Folia`-safe region / entity / global / async schedulers.
 *
 * Each dispatcher only ever hands the scheduler API a plain `Runnable` — the resumption of
 * whatever coroutine called `withContext(...)` — and never blocks the thread it dispatches
 * onto waiting for that resumption to finish.
 */
class PaperTracelSchedulers(private val plugin: Plugin) : TracelSchedulers {
    override fun region(location: HolderId.Block): CoroutineDispatcher = RegionDispatcher(plugin, location.toBukkitLocation())

    override fun entity(entity: UUID): CoroutineDispatcher = EntityDispatcher(plugin, entity)

    override val global: CoroutineDispatcher = GlobalDispatcher(plugin)

    override val async: CoroutineDispatcher = AsyncDispatcher(plugin)

    override val storage: CoroutineDispatcher =
        Executors.newSingleThreadExecutor(NamedThreadFactory("Tracel-Storage")).asCoroutineDispatcher()

    private fun HolderId.Block.toBukkitLocation(): Location {
        val bukkitWorld = Bukkit.getWorld(world.uuid) ?: error("world $world is not loaded")
        return Location(bukkitWorld, x.toDouble(), y.toDouble(), z.toDouble())
    }
}

private class RegionDispatcher(private val plugin: Plugin, private val location: Location) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Bukkit.getRegionScheduler().execute(plugin, location) { block.run() }
    }
}

private class EntityDispatcher(private val plugin: Plugin, private val entityId: UUID) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val entity = Bukkit.getEntity(entityId)
        if (entity == null) {
            Bukkit.getGlobalRegionScheduler().execute(plugin) { block.run() }
            return
        }
        entity.scheduler.execute(plugin, { block.run() }, { block.run() }, 0L)
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

private class NamedThreadFactory(private val name: String) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, name)
}
