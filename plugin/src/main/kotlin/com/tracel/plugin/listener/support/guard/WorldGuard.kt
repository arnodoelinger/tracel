package com.tracel.plugin.listener.support.guard

import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.ExpiringMap

private const val WRITTEN_TTL_MS = 1_000L
private const val WRITTEN_KEPT = 1 shl 20

/**
 * Restore must not log itself, that's why we need self managed world guard. Putting a wall back fires
 * the same events as building one; if those land in the store, the next rollback over the same window
 * undoes the repair.
 *
 * Every restorer write is nested in [whileRestoring] on the region thread; listeners check
 * [isRestoring] first. Same thread-local as [SpawnGuard]: events are synchronous and
 * nested, so the flag marks exactly those writes.
 */
class SelfManagedWorldGuard {
    val isRestoring: Boolean get() = active.get()

    private val active = ThreadLocal.withInitial { false }
    private val written = ExpiringMap<BlockPos, Long>(WRITTEN_TTL_MS, WRITTEN_KEPT)

    /** Sets the flag for the duration of [action], restoring it afterward. */
    fun <T> whileRestoring(action: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return action()
        } finally {
            active.set(previous)
        }
    }

    /**
     * Sets the flag without a block, for a restore that spans ticks and must drop it between them.
     * Pair with [leave], passing back what this returned.
     */
    fun enter(): Boolean {
        val previous = active.get()
        active.set(true)
        return previous
    }

    /** Restores the flag to what [enter] returned. */
    fun leave(previous: Boolean) {
        active.set(previous)
    }

    /**
     * Marks [cells] as written, so that listeners can ignore them. The mark expires after a second.
     *
     * This is used for the same reason as [isRestoring].
     */
    fun wrote(cells: Iterable<BlockPos>, nanos: Long = System.nanoTime()) {
        written.putAll(cells, nanos)
    }

    /**
     * Whether a restore wrote [cell] after a listener read it at [readNanos]: that listener's before is from ahead of
     * the restore, and its after is the restore.
     *
     * One read after the write is the world's own, and stands.
     */
    fun wroteSince(cell: BlockPos, readNanos: Long): Boolean = (written[cell] ?: return false) >= readNanos
}
