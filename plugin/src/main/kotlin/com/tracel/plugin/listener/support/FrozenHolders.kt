package com.tracel.plugin.listener.support

import com.tracel.model.holder.HolderId
import java.util.concurrent.ConcurrentHashMap

/**
 * Holders mid-restore, closed to hoppers / droppers / minecarts.
 *
 * The lot lease dies when the ledger journals — before `Bukkit` restore. In that gap a hopper
 * under a hopper minecart steals the take, so the world and the books disagree.
 *
 * Players are left alone: freeze-out of a click is worse than a shortfall the differ already sees.
 */
class FrozenHolders {
    private val frozen = ConcurrentHashMap<HolderId, Int>()

    /** Whether an automated transfer touching [holder] has to wait. */
    fun isFrozen(holder: HolderId): Boolean = frozen.containsKey(holder)

    /** Refcount: two jobs on one chest, first finish must not thaw the second. */
    fun freeze(holders: Collection<HolderId>) {
        for (holder in holders) frozen.merge(holder, 1, Int::plus)
    }

    /** Reopens what [freeze] closed. */
    fun thaw(holders: Collection<HolderId>) {
        for (holder in holders) {
            frozen.computeIfPresent(holder) { _, held -> if (held <= 1) null else held - 1 }
        }
    }

    /** [freeze] for the duration of [work], whatever [work] does or throws. */
    suspend fun <T> whileFrozen(holders: Collection<HolderId>, work: suspend () -> T): T {
        // Snapshot the set: thawing a different collection leaks a freeze for the life of the server
        val held = holders.toList()
        freeze(held)
        try {
            return work()
        } finally {
            thaw(held)
        }
    }
}
