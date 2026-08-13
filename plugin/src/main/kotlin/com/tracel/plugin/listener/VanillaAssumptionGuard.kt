package com.tracel.plugin.listener

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Vanilla assumptions are the places in this codebase where we do something risky, assuming that
 * nothing else will interfere with it.
 *
 * If that assumption fails live, this guard will log a message and prevent the risky path from
 * being attempted again for the rest of this server run.
 */
class VanillaAssumptionGuard {
    private val logger = Logger.getLogger(VanillaAssumptionGuard::class.java.name)
    private val tripped = ConcurrentHashMap<String, AtomicBoolean>()
    private val watches = ConcurrentHashMap<WatchKey, Long>()

    /** Whether this assumption has already failed live this server run — the risky path must not be attempted again. */
    fun isTripped(assumption: String): Boolean = tripped[assumption]?.get() ?: false

    /** Call immediately after doing the risky thing (clearing a container, suppressing a block's drop). */
    fun watch(assumption: String, world: UUID, x: Int, y: Int, z: Int) {
        val now = System.currentTimeMillis()
        watches[WatchKey(assumption, world, x, y, z)] = now
        if (watches.size > PRUNE_THRESHOLD) {
            watches.entries.removeIf { now - it.value > WATCH_WINDOW_MILLIS }
        }
    }

    /**
     * Called from [ItemEntityCaptureListener] for every spawn that wasn't self-managed — if it
     * lands inside an active watch, the assumption named by that watch just failed live.
     */
    fun checkSurpriseSpawn(world: UUID, x: Double, y: Double, z: Double) {
        val now = System.currentTimeMillis()
        val radiusSq = WATCH_RADIUS * WATCH_RADIUS
        for ((key, atMillis) in watches) {
            if (key.world != world || now - atMillis > WATCH_WINDOW_MILLIS) continue
            val dx = key.x + 0.5 - x
            val dy = key.y + 0.5 - y
            val dz = key.z + 0.5 - z
            if (dx * dx + dy * dy + dz * dz <= radiusSq) trip(key.assumption)
        }
    }

    private fun trip(assumption: String) {
        if (tripped.getOrPut(assumption) { AtomicBoolean(false) }.compareAndSet(false, true)) {
            logger.log(
                Level.SEVERE,
                "@VanillaAssumption \"$assumption\" just failed live: an item spawned that this " +
                    "code did not itself create, right where / when it assumed nothing else would. " +
                    "Falling back to the always-safe path for \"$assumption\" for the rest of this " +
                    "server run.",
            )
        }
    }

    private data class WatchKey(val assumption: String, val world: UUID, val x: Int, val y: Int, val z: Int)

    private companion object {
        const val WATCH_WINDOW_MILLIS = 3_000L
        const val WATCH_RADIUS = 1.5
        const val PRUNE_THRESHOLD = 256
    }
}
