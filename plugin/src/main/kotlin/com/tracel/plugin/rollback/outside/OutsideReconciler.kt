package com.tracel.plugin.rollback.outside

import com.tracel.api.addon.RollbackStep
import com.tracel.engine.log.RolledBack
import com.tracel.engine.rollback.outside.OutsideStep
import com.tracel.engine.rollback.outside.OutsideSteps
import com.tracel.plugin.api.addon.BoundReversible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Brings addons in line with history.
 *
 * The rolled-back marks say what a step should be; [OutsideSteps] says what its
 * addon was last told; where they differ, the addon is told again.
 *
 * Nothing here trusts the order of events. For example, a crash between a rollback and
 * its addon hearing of it, an undo the addon missed, an addon that was off at the time,
 * all end the same way, the next time this runs.
 */
internal class OutsideReconciler(
    private val rolledBack: RolledBack,
    private val steps: OutsideSteps,
    private val scope: CoroutineScope,
    private val reversible: (kind: String) -> BoundReversible?,
) {
    /**
     * Tells the addons behind [pending] what history now says of them;
     * those whose addon is off wait for it.
     */
    suspend fun reconcile(pending: List<OutsideStep>) {
        if (pending.isEmpty()) return
        val taken = rolledBack.of(pending.map { it.seq })
        for ((seq, kind, text, undone1, generation1, job) in pending) {
            val undone = seq in taken
            if (undone == undone1) continue
            val bound = reversible(kind) ?: continue
            val generation = generation1 + 1
            bound.tell(undone, RollbackStep("$seq:$generation", job), text) { went ->
                if (went) scope.launch { steps.told(seq, undone, generation) }
            }
        }
    }

    /** Every step of [kind], for an addon that has just come back. */
    suspend fun reconcileKind(kind: String) = reconcile(steps.ofKind(kind))
}
