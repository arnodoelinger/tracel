package com.tracel.plugin.governor

import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import kotlin.coroutines.resume

/**
 * The nanoseconds a restore may hold the tick before it hands the rest back.
 *
 * [mspt] is the region's own average tick time over the last five seconds, which includes what this restore took
 * itself; [ownMillis] is that, so what is left is everyone else's load.
 */
internal fun allowance(settings: GovernorSettings, mspt: Double?, ownMillis: Double = 0.0): Long {
    val millis = if (mspt == null || mspt.isNaN()) {
        BLIND_MILLIS
    } else {
        val others = (mspt - ownMillis).coerceAtLeast(0.0)
        (TICK_MILLIS - others).coerceAtLeast(0.0) * HEADROOM_SHARE
    }
    return (millis * NANOS_PER_MILLI).toLong().coerceIn(settings.minNanos, settings.maxNanos)
}

/**
 * Keeps a rollback from holding a region's tick: reads how loaded the region is, says how long a [Throttle] may run,
 * and parks it until the region's next tick.
 */
class TickGovernor(private val plugin: Plugin, val settings: GovernorSettings = GovernorSettings()) {
    /** Region thread only. Off one, `Folia` refuses, and the answer is the blind default. */
    fun allowanceNanos(ownNanos: Long = 0L): Long {
        val mspt = runCatching { Bukkit.getAverageTickTime() }.getOrNull()
        return allowance(settings, mspt, ownNanos / NANOS_PER_MILLI)
    }

    /** Resumes on the region that owns the chunk, one tick from now. */
    internal suspend fun nextTick(world: World, chunkX: Int, chunkZ: Int) {
        suspendCancellableCoroutine { continuation ->
            val task = runCatching {
                Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, { continuation.resume(Unit) }, 1L)
            }.getOrElse {
                // Plugin shutting down: nothing will ever tick this, so carry on and let cancellation land
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }
}
