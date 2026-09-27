package com.tracel.plugin.listener.support.guard

import com.tracel.model.holder.HolderId
import com.tracel.plugin.listener.session.FreezeGuardListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Holders mid-restore, closed to hoppers / droppers / minecarts.
 *
 * The lot lease dies when the ledger journals — before `Bukkit` restore. In that gap a hopper
 * under a hopper minecart steals the take, so the world and the books disagree.
 *
 * Players and piles too, for the short window a rollback moves them: a click held for a moment beats
 * a thief keeping stacks the victim was paid for.
 *
 * @see [FreezeGuardListener]
 */
class FreezeGuard {
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
