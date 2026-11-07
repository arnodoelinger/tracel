package com.tracel.plugin.rollback.outside

import com.tracel.api.addon.RollbackStep
import com.tracel.engine.rollback.outside.OutsideStep
import com.tracel.plugin.api.addon.BoundReversible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Brings addons in line with history.
 *
 * The rolled-back marks say what a step should be ([takenOf]); the kept steps say what its
 * addon was last told ([ofKind], [told]); where they differ, the addon is told again.
 *
 * Nothing here trusts the order of events. For example, a crash between a rollback and
 * its addon hearing of it, an undo the addon missed, an addon that was off at the time,
 * all end the same way, the next time this runs.
 */
internal class OutsideReconciler(
    private val takenOf: suspend (seqs: Collection<Long>) -> Map<Long, Long>,
    private val ofKind: suspend (kind: String) -> List<OutsideStep>,
    private val told: suspend (seq: Long, undone: Boolean, generation: Int) -> Unit,
    private val scope: CoroutineScope,
    private val reversible: (kind: String) -> BoundReversible?,
) {
    /**
     * Tells the addons behind [pending] what history now says of them;
     * those whose addon is off wait for it.
     */
    suspend fun reconcile(pending: List<OutsideStep>) {
        if (pending.isEmpty()) return
        val taken = takenOf(pending.map { it.seq })
        for ((seq, kind, text, undone1, generation1, job) in pending) {
            val undone = seq in taken
            if (undone == undone1) continue
            val bound = reversible(kind) ?: continue
            val generation = generation1 + 1
            bound.tell(undone, RollbackStep("$seq:$generation", job), text) { went ->
                if (went) scope.launch { told(seq, undone, generation) }
            }
        }
    }

    /** Every step of [kind], for an addon that has just come back. */
    suspend fun reconcileKind(kind: String) = reconcile(ofKind(kind))
}
