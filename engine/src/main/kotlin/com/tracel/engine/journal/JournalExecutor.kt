package com.tracel.engine.journal

import com.tracel.engine.ownership.LotLease
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.model.holder.HolderId
import com.tracel.model.id.TxnId

/**
 * Runs a [RollbackPlan] step by step, recording progress in a [Journal] as it goes.
 *
 * Takes a [LotLease], not a bare [com.tracel.model.id.RollbackJobId]: applying a plan without
 * having reserved every lot it touches is exactly the race two admins running overlapping
 * `/tracel rollback apply` commands could hit, and [LotLeaseRegistry.acquire]
 * is the only way to obtain one — see there for why that makes it impossible to call this
 * having skipped the reservation, not merely discouraged.
 *
 * [leases] is released automatically once a run finishes successfully — a completed job has
 * let go of its material, there is nothing left to protect. It is deliberately not released
 * when [crashPoint] (or a real crash) interrupts a run partway through: the lease has to
 * survive exactly as long as the job might still resume, which is why [LotLeaseRegistry] itself
 * is durable rather than an in-process lock.
 */
public class JournalExecutor(
    private val executor: RollbackExecutor,
    private val journal: Journal,
    private val leases: LotLeaseRegistry,
) {
    public suspend fun execute(
        lease: LotLease,
        plan: RollbackPlan,
        restoreTo: HolderId,
        txn: TxnId,
        crashPoint: CrashPoint = CrashPoint.None,
    ) {
        require(lease.lotIds.containsAll(plan.touchedLots)) {
            "lease held by job ${lease.job} does not cover every lot this plan touches"
        }
        val job = lease.job

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

        leases.release(job)
    }
}
