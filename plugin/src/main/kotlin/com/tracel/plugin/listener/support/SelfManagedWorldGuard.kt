package com.tracel.plugin.listener.support

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
 * [isRestoring] first. Same thread-local as [SelfManagedSpawnGuard]: events are synchronous and
 * nested, so the flag marks exactly those writes.
 */
class SelfManagedWorldGuard {
    private val active = ThreadLocal.withInitial { false }

    fun <T> whileRestoring(action: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return action()
        } finally {
            active.set(previous)
        }
    }

    val isRestoring: Boolean get() = active.get()

    private val written = ExpiringMap<BlockPos, Unit>(WRITTEN_TTL_MS, WRITTEN_KEPT)

    /** Region thread, right after the write. */
    fun wrote(cells: Iterable<BlockPos>) {
        for (cell in cells) written.put(cell, Unit)
    }

    /** Whether a restore wrote [cell] within the last second. */
    fun justWrote(cell: BlockPos): Boolean = cell in written
}
