package com.tracel.engine.journal

import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * Runs a [RollbackPlan] step by step, recording progress in a [Journal] as it
 * goes.
 */
public class JournalExecutor(
    private val executor: RollbackExecutor,
    private val journal: Journal,
) {
    public suspend fun execute(
        job: RollbackJobId,
        plan: RollbackPlan,
        restoreTo: HolderId,
        txn: TxnId,
        crashPoint: CrashPoint = CrashPoint.None,
    ) {
        for (index in plan.steps.indices) {
            if (journal.isCompleted(job, index)) continue
            crashPoint.checkBefore(index)
            executor.apply(job, plan.steps[index], txn)
            journal.markCompleted(job, index)
        }

        val releaseIndex = plan.steps.size
        if (!journal.isCompleted(job, releaseIndex)) {
            crashPoint.checkBefore(releaseIndex)
            executor.release(job, restoreTo, executor.escrowItemKeys(plan), txn)
            journal.markCompleted(job, releaseIndex)
        }
    }
}
