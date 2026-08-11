package com.tracel.engine.rollback

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * The safe way to run a rollback end to end: plan -> acquire -> verify -> apply — never
 * plan -> apply directly. The world (or another job entirely) can move between the moment
 * [RollbackPlanner.plan] reads the ledger and the moment [LotLeaseRegistry.acquire] actually
 * reserves anything; a plan computed against a state that has since changed is stale, and
 * applying it anyway is exactly the race this class exists to close.
 *
 * The check is deliberately cheap: replanning from the same [RollbackStep.Take] / [RollbackStep.Mint]
 * / etc. roots and comparing the result to the original plan by equality is enough to detect anything
 * that would actually change what this job does — no separate "verify" primitive needed.
 */
public class RollbackJobCoordinator(
    private val repo: LotRepository,
    private val worldQuery: WorldQuery,
    private val leases: LotLeaseRegistry,
    private val journalExecutor: JournalExecutor,
) {
    public suspend fun run(
        job: RollbackJobId,
        rootLots: List<LotId>,
        restoreTo: HolderId,
        txn: TxnId,
        crashPoint: CrashPoint = CrashPoint.None,
    ): RollbackOutcome {
        val planner = RollbackPlanner(repo, worldQuery)
        val plan = planner.plan(rootLots)

        val lease = when (val acquisition = leases.acquire(job, plan.touchedLots)) {
            is LeaseAcquisition.Denied -> return RollbackOutcome.Blocked(acquisition.conflicts)
            is LeaseAcquisition.Granted -> acquisition.lease
        }

        val verified = planner.plan(rootLots)
        if (verified != plan) {
            leases.release(job)
            return RollbackOutcome.Stale(verified)
        }

        journalExecutor.execute(lease, plan, restoreTo, txn, crashPoint)
        return RollbackOutcome.Applied(plan)
    }
}

/** What [RollbackJobCoordinator.run] actually did. */
public sealed interface RollbackOutcome {
    /** The plan ran to completion; the lease it held has already been released. */
    public data class Applied(public val plan: RollbackPlan) : RollbackOutcome

    /** Another job already holds one or more of the lots this plan needs — nothing was touched. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : RollbackOutcome

    /** The world changed between planning and reserving; [replan] is what the plan looks like now. */
    public data class Stale(public val replan: RollbackPlan) : RollbackOutcome
}
