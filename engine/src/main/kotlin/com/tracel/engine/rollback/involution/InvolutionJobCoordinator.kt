package com.tracel.engine.rollback.involution

import com.tracel.annotations.Journaled
import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.Journal
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.job.RollbackJobRepository
import com.tracel.model.holder.HolderId
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
    private val nextTxnId: suspend () -> TxnId,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    @Journaled
    public suspend fun undo(
        job: RollbackJobId,
        crashPoint: CrashPoint = CrashPoint.None,
        vanished: Set<HolderId> = emptySet(),
    ): InvolutionOutcome {
        val record = jobs.find(job) ?: return InvolutionOutcome.NotFound

        val lease = when (val acquisition = leases.acquire(job, record.plan.touchedLots)) {
            is LeaseAcquisition.Denied -> return InvolutionOutcome.Blocked(acquisition.conflicts)
            is LeaseAcquisition.Granted -> acquisition.lease
        }

        // Everything past the acquisition runs inside this, so a step that throws still gives the
        // lease back. It used to escape: the undo failed, the lots stayed leased
        // to a job that was no longer running, and every rollback and undo afterwards was refused,
        // for good, until a restart.
        return leases.holdingFor(job) {
            val steps = InvolutionPlanner(repo).plan(record, vanished)
            val n = steps.size
            if (n == 0) return@holdingFor InvolutionOutcome.Undone(steps)

            val done = journal.completed(job, n)
            // Checked before touching anything: the ledger step loop below is idempotent via the
            // journal, but physical restoration is not (it has no memory of its own). A caller must
            // not re-run it for a job already finished, or a repeated undo physically re-adds
            // material on the give-back side every single call.
            if (done.size == n) return@holdingFor InvolutionOutcome.AlreadyUndone(steps)

            val words = LongArray((n + 63) ushr 6)
            for (index in done) {
                if (index in 0..<n) setBit(words, index)
            }

            val stride = if (crashPoint == CrashPoint.None) batchSize else 1
            var from = 0
            while (from < n) {
                val end = from + stride
                val until = if (end < n) end else n
                var index = from
                while (index < until && isSet(words, index)) index++
                if (index == until) {
                    from = until
                    continue
                }
                executor.atomically {
                    while (index < until) {
                        if (!isSet(words, index)) {
                            crashPoint.checkBefore(index)
                            executor.apply(lease, steps[index], nextTxnId())
                            journal.markCompleted(job, index)
                            setBit(words, index)
                        }
                        index++
                    }
                }
                from = until
            }

            InvolutionOutcome.Undone(steps)
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 4096

        @Suppress("NOTHING_TO_INLINE")
        private inline fun isSet(words: LongArray, index: Int): Boolean =
            words[index ushr 6] and (1L shl (index and 63)) != 0L

        @Suppress("NOTHING_TO_INLINE")
        private inline fun setBit(words: LongArray, index: Int) {
            val word = index ushr 6
            words[word] = words[word] or (1L shl (index and 63))
        }
    }
}

/** What [InvolutionJobCoordinator.undo] actually did. */
public sealed interface InvolutionOutcome {
    /** Job has no [RollbackJobRecord] on record — either it never ran, or it already predates this feature. */
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
