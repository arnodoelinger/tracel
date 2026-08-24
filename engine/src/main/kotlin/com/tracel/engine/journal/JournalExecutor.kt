package com.tracel.engine.journal

import com.tracel.engine.ownership.LotLease
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.model.holder.HolderId
import com.tracel.model.id.TxnId

/**
 * Runs a [RollbackPlan] step by step, recording progress in a [Journal] as it goes.
 */
public class JournalExecutor(
    private val executor: RollbackExecutor,
    private val journal: Journal,
    private val leases: LotLeaseRegistry,
    private val nextTxnId: suspend () -> TxnId,
) {
    public suspend fun execute(
        lease: LotLease,
        plan: RollbackPlan,
        restoreTo: HolderId,
        crashPoint: CrashPoint = CrashPoint.None,
    ) {
        require(lease.lotIds.containsAll(plan.touchedLots)) {
            "lease held by job ${lease.job} does not cover every lot this plan touches"
        }
        val job = lease.job

        for (index in plan.steps.indices) {
            if (journal.isCompleted(job, index)) continue
            crashPoint.checkBefore(index)
            executor.atomically {
                executor.apply(job, plan.steps[index], nextTxnId())
                journal.markCompleted(job, index)
            }
        }

        val releaseIndex = plan.steps.size
        if (!journal.isCompleted(job, releaseIndex)) {
            crashPoint.checkBefore(releaseIndex)
            executor.atomically {
                executor.release(job, restoreTo, executor.escrowItemKeys(plan), nextTxnId())
                journal.markCompleted(job, releaseIndex)
            }
        }

        leases.release(job)
    }
}
