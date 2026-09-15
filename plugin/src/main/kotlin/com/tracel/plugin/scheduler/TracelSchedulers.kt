package com.tracel.plugin.scheduler

import com.tracel.model.holder.HolderId
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.plugin.util.ownsChunkAt
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import java.util.concurrent.Executors

/** [TracelSchedulers] backed by `Paper`'s `Folia`-safe schedulers. */
class TracelSchedulers(
    private val plugin: Plugin,
    override val storage: CoroutineDispatcher,
) : TracelSchedulers {
    override fun region(location: HolderId.Block): CoroutineDispatcher = RegionDispatcher(plugin, location)

    override fun entity(entity: UUID): CoroutineDispatcher = EntityDispatcher(plugin, entity)

    override val global: CoroutineDispatcher = GlobalDispatcher(plugin)

    override val async: CoroutineDispatcher = AsyncDispatcher(plugin)
}

/**
 * Runs `block` on the server, or somewhere at all if the server will not have it.
 *
 * These dispatchers hand work to a `Folia` scheduler, which refuses work once the plugin
 * is disabled. Cancellation still needs to be dispatched: resuming a suspended coroutine
 * lets it unwind with cancellation instead of leaving it suspended forever.
 *
 * This is a last resort for shutdown unwinding when the server scheduler is unavailable.
 * And before someone clean code purist calls this a fucking hack: standard coroutine
 * cancellation requires a working dispatcher to actually run cleanup logic. That's it.
 *
 * This class forces cancellation continuations to run no matter how broken the server
 * state is.
 */
private object LastResort {
    private val thread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tracel-Shutdown").apply { isDaemon = true }
    }

    /** Run a worker thread on whatever is available. */
    fun run(block: Runnable) {
        // If even that is gone, the calling thread does it: nothing here can afford to be dropped
        runCatching { thread.execute(block) }.onFailure { runCatching { block.run() } }
    }
}

private inline fun onServer(plugin: Plugin, block: Runnable, give: () -> Unit) {
    if (!plugin.isEnabled) {
        LastResort.run(block)
        return
    }
    try {
        give()
    } catch (_: IllegalPluginAccessException) {
        LastResort.run(block)
    }
}

private class RegionDispatcher(private val plugin: Plugin, private val holder: HolderId.Block) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val world = Bukkit.getWorld(holder.world.uuid)
        if (world == null) {
            onServer(plugin, block) { Bukkit.getGlobalRegionScheduler().execute(plugin) { block.run() } }
            return
        }
        if (ownsChunkAt(world, holder.x, holder.z)) {
            block.run()
            return
        }
        val location = Location(world, holder.x.toDouble(), holder.y.toDouble(), holder.z.toDouble())
        onServer(plugin, block) { Bukkit.getRegionScheduler().execute(plugin, location) { block.run() } }
    }
}

private class EntityDispatcher(private val plugin: Plugin, private val entityId: UUID) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // Bukkit.getEntity(UUID) itself touches chunk / entity state, so it may only be called
        // from a thread that already owns that state, never straight off dispatch()'s own caller
        onServer(plugin, block) {
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
}

private class GlobalDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        onServer(plugin, block) { Bukkit.getGlobalRegionScheduler().execute(plugin) { block.run() } }
    }
}

@OptIn(InternalCoroutinesApi::class)
private class AsyncDispatcher(private val plugin: Plugin) : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        onServer(plugin, block) { Bukkit.getAsyncScheduler().runNow(plugin) { block.run() } }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        if (!plugin.isEnabled) {
            // Nothing left to wait for. Waking it now lets it see it has been canceled
            LastResort.run { continuation.resume(Unit) { _, _, _ -> } }
            return
        }
        val task = runCatching {
            Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                { continuation.resume(Unit) { _, _, _ -> } },
                timeMillis.coerceAtLeast(1),
                TimeUnit.MILLISECONDS,
            )
        }.getOrElse {
            LastResort.run { continuation.resume(Unit) { _, _, _ -> } }
            return
        }
        continuation.invokeOnCancellation { task.cancel() }
    }
}
