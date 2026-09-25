package com.tracel.engine.rollback.job

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.ownership.LotLease
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.WorldQuery
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

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
    private val jobs: RollbackJobRepository,
    private val ledgerVersion: (suspend () -> Long)? = null,
    private val changedSince: (suspend (Collection<LotId>, Long) -> Boolean)? = null,
) {
    /**
     * @param prepared a plan the caller has already worked out, with [preparedAt] the ledger
     * version read just before working it out. Both or neither: the witness is what makes
     * handing in a plan as safe as computing one here, and a plan without one would skip the
     * staleness check rather than pass it.
     */
    public suspend fun run(
        job: RollbackJobId,
        rootLots: List<LotId>,
        target: RollbackTarget,
        vanished: Set<HolderId> = emptySet(),
        crashPoint: CrashPoint = CrashPoint.None,
        recordsOwnJob: Boolean = true,
        prepared: RollbackPlan? = null,
        preparedAt: Long? = null,
        structural: Boolean = true,
        covered: Set<HolderId>? = null,
    ): RollbackOutcome = when (val reservation = reserve(job, rootLots, target, vanished, prepared, preparedAt, structural, covered)) {
        is Reservation.Blocked -> RollbackOutcome.Blocked(reservation.conflicts)
        is Reservation.Stale -> RollbackOutcome.Stale(reservation.replan)
        is Reservation.Granted -> apply(reservation, target, crashPoint, recordsOwnJob)
    }

    /**
     * Plans, reserves and verifies — everything that can still say no.
     *
     * Split out from [apply] so a caller can find out whether the job is going to happen before
     * it starts changing the world. A rollback puts blocks back, and it cannot take them back out
     * again if the lots turn out to be leased to somebody else; the only honest way to run the two
     * halves at the same time is to settle the question first.
     *
     * @param prepared a plan the caller has already worked out, with [preparedAt] the ledger
     * version read just before working it out. Both or neither: the witness is what makes
     * handing in a plan as safe as computing one here, and a plan without one would skip the
     * staleness check rather than pass it.
     */
    public suspend fun reserve(
        job: RollbackJobId,
        rootLots: List<LotId>,
        target: RollbackTarget,
        vanished: Set<HolderId> = emptySet(),
        prepared: RollbackPlan? = null,
        preparedAt: Long? = null,
        structural: Boolean = true,
        covered: Set<HolderId>? = null,
    ): Reservation {
        val planner = RollbackPlanner(repo, worldQuery, vanished = vanished, structural = structural, covered = covered, target = target)
        val plan: RollbackPlan
        val planned: Long?
        if (prepared != null && preparedAt != null) {
            plan = prepared
            planned = preparedAt
        } else {
            planned = ledgerVersion?.invoke()
            plan = planner.plan(rootLots)
        }

        val lease = when (val acquisition = leases.acquire(job, plan.touchedLots)) {
            is LeaseAcquisition.Denied -> return Reservation.Blocked(acquisition.conflicts)
            is LeaseAcquisition.Granted -> acquisition.lease
        }

        // Only worth replanning if one of its own lots changed: on a live server something always did
        val moved = planned == null ||
            (changedSince?.invoke(plan.touchedLots, planned) ?: (ledgerVersion?.invoke() != planned))
        if (moved) {
            val verifiedAt = ledgerVersion?.invoke()
            val verified = try {
                planner.plan(rootLots)
            } catch (failure: Throwable) {
                leases.release(job)
                throw failure
            }
            if (verified != plan) {
                leases.release(job)
                return Reservation.Stale(verified, verifiedAt)
            }
        }

        return Reservation.Granted(job, lease, plan)
    }

    /** Gives back what [reserve] leased when [apply] never ran: a structure pass failing first left the lots leased till restart. */
    public suspend fun cancel(reservation: Reservation.Granted) {
        leases.release(reservation.job)
    }

    /** Runs what [reserve] granted. Nothing here can refuse; the refusing was done up there. */
    public suspend fun apply(
        reservation: Reservation.Granted,
        target: RollbackTarget,
        crashPoint: CrashPoint = CrashPoint.None,
        recordsOwnJob: Boolean = true,
    ): RollbackOutcome {
        journalExecutor.execute(reservation.lease, reservation.plan, target, crashPoint)

        if (recordsOwnJob) jobs.save(RollbackJobRecord(reservation.job, reservation.plan, target))
        return RollbackOutcome.Applied(reservation.plan)
    }
}
