package com.tracel.engine.rollback.involution

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.Journal
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.RollbackJobCoordinator
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackJobRepository
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * The undo-side mirror of [RollbackJobCoordinator]: looks an already-applied job up in [jobs],
 * turns it into [InvolutionStep]s via [InvolutionPlanner], and runs them step by step through
 * [executor], recording progress in its own [journal] so a crash mid-undo resumes instead of
 * re-running steps that already happened.
 *
 * Does not replan-and-compare the way [RollbackJobCoordinator]. Keep that in mind.
 */
public class InvolutionJobCoordinator(
    private val jobs: RollbackJobRepository,
    private val repo: LotRepository,
    private val leases: LotLeaseRegistry,
    private val executor: InvolutionExecutor,
    private val journal: Journal,
    private val nextTxnId: () -> TxnId,
) {
    public fun undo(job: RollbackJobId, crashPoint: CrashPoint = CrashPoint.None): InvolutionOutcome {
        val record = jobs.find(job) ?: return InvolutionOutcome.NotFound

        val lease = when (val acquisition = leases.acquire(job, record.plan.touchedLots)) {
            is LeaseAcquisition.Denied -> return InvolutionOutcome.Blocked(acquisition.conflicts)
            is LeaseAcquisition.Granted -> acquisition.lease
        }

        val steps = InvolutionPlanner(repo).plan(record)
        // Checked before touching anything: the ledger step loop below is idempotent via
        // journal.isCompleted, but physical restoration is not — it has no memory of its own. A
        // caller must not re-run it for a job already finished, or a repeated /tracel rollback
        // undo physically re-adds material on the give-back side every single call.
        val alreadyDone = steps.indices.all { journal.isCompleted(job, it) }

        for (index in steps.indices) {
            if (journal.isCompleted(job, index)) continue
            crashPoint.checkBefore(index)
            executor.apply(lease, steps[index], nextTxnId())
            journal.markCompleted(job, index)
        }

        leases.release(job)
        return if (alreadyDone) InvolutionOutcome.AlreadyUndone(steps) else InvolutionOutcome.Undone(steps)
    }
}

/** What [InvolutionJobCoordinator.undo] actually did. */
public sealed interface InvolutionOutcome {
    /** [job] has no [RollbackJobRecord] on record — either it never ran, or it already predates this feature. */
    public data object NotFound : InvolutionOutcome

    /** Another job already holds one or more of the lots this undo needs — nothing was touched. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : InvolutionOutcome

    /**
     * Every step ran this call (or had already run, on a resumed crash partway through) and the
     * lease was released. Physical restoration for [steps] has never been attempted for this job —
     * safe to run now.
     */
    public data class Undone(public val steps: List<InvolutionStep>) : InvolutionOutcome

    /**
     * Every step was already marked completed before this call started — a previous `undo` already
     * finished this job. A caller must not attempt physical restoration for [steps] again: unlike
     * the ledger-level journal, physical restoration can't tell "already done" from "needs doing"
     * on its own, and re-running it duplicates whatever side previously succeeded.
     */
    public data class AlreadyUndone(public val steps: List<InvolutionStep>) : InvolutionOutcome
}
