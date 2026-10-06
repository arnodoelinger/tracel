package com.tracel.engine.rollback.job

import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.engine.rollback.job.record.RollbackJobRecord
import com.tracel.engine.rollback.job.record.RollbackJobRepository
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.journal.crash.CrashPoint
import com.tracel.engine.rollback.lease.Leases
import com.tracel.engine.rollback.lease.acquisition.LeaseAcquisition
import com.tracel.engine.rollback.plan.PreparedPlan
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.WorldQuery
import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId

/**
 * The safe way to run a rollback end to end: plan -> acquire -> verify -> apply — never
 * plan -> apply directly. The world (or another job entirely) can move between the moment
 * [RollbackPlanner.plan] reads the ledger and the moment [Leases.acquire] actually
 * reserves anything; a plan computed against a state that has since changed is stale, and
 * applying it anyway is exactly the race this class exists to close.
 */
public class RollbackJobCoordinator(
    private val repo: LotRepository,
    private val worldQuery: WorldQuery,
    private val leases: Leases,
    private val journalExecutor: JournalExecutor,
    private val jobs: RollbackJobRepository,
    private val ledgerVersion: (suspend () -> Long)? = null,
    private val changedSince: (suspend (Collection<LotId>, Long) -> Boolean)? = null,
) {
    /** Run a rollback job. */
    public suspend fun run(
        job: RollbackJobId,
        rootLots: List<LotId>,
        target: RollbackTarget,
        vanished: Set<HolderId> = emptySet(),
        crashPoint: CrashPoint = CrashPoint.None,
        recordsOwnJob: Boolean = true,
        prepared: PreparedPlan? = null,
        structural: Boolean = true,
        covered: Set<HolderId>? = null,
    ): RollbackOutcome =
        when (val reservation = reserve(job, rootLots, target, vanished, prepared, structural, covered)) {
            is Reservation.Blocked -> RollbackOutcome.Blocked(reservation.conflicts)
            is Reservation.Stale -> RollbackOutcome.Stale(reservation.replan)
            is Reservation.Granted -> apply(reservation, target, crashPoint, recordsOwnJob)
        }

    /** Plans, reserves and verifies — everything that can still say no. */
    public suspend fun reserve(
        job: RollbackJobId,
        rootLots: List<LotId>,
        target: RollbackTarget,
        vanished: Set<HolderId> = emptySet(),
        prepared: PreparedPlan? = null,
        structural: Boolean = true,
        covered: Set<HolderId>? = null,
    ): Reservation {
        val planner = RollbackPlanner(
            repo = repo,
            worldQuery = worldQuery,
            vanished = vanished,
            structural = structural,
            covered = covered,
            target = target
        )
        val plan: RollbackPlan
        val planned: Long?
        if (prepared != null) {
            plan = prepared.plan
            planned = prepared.witness
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

    /**
     * Gives back what [reserve] leased when [apply] never ran: a structure pass failing first left the lots leased till
     * restart.
     */
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
