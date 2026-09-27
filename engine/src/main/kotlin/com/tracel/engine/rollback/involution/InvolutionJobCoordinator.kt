package com.tracel.engine.rollback.involution

import com.tracel.annotations.Journaled
import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.Journal
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.ownership.LotLease
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.job.RollbackJobRepository
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * Journaled undo of an applied job.
 *
 * Does not replan-and-compare; the recorded plan is the truth.
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
    ): InvolutionOutcome {
        val record = jobs.find(job) ?: return InvolutionOutcome.NotFound

        val lease = when (val acquisition = leases.acquire(job, record.plan.touchedLots)) {
            is LeaseAcquisition.Denied -> return InvolutionOutcome.Blocked(acquisition.conflicts)
            is LeaseAcquisition.Granted -> acquisition.lease
        }

        // holdingFor must wrap the rest
        return leases.holdingFor(job) {
            val resuming = journal.completed(job, Int.MAX_VALUE).isNotEmpty()
            val steps = InvolutionPlanner(repo).plan(record, resuming)
            val n = steps.size
            if (n == 0) {
                val prior = journal.completed(job, 1)
                return@holdingFor if (prior.isNotEmpty()) {
                    InvolutionOutcome.AlreadyUndone(steps)
                } else {
                    InvolutionOutcome.Undone(steps)
                }
            }

            val done = journal.completed(job, n)
            // Journal is idempotent; physical restore is not. AlreadyUndone means "do not give items again!".
            if (done.size == n) return@holdingFor InvolutionOutcome.AlreadyUndone(steps)

            val words = LongArray((n + 63) ushr 6)
            for (index in done) {
                if (index in 0..<n) setBit(words, index)
            }

            // Only leftover steps: a resume already spent journaled withdrawals; re-checking those fails a half-done job
            executor.checkSatisfiable(lease, steps.filterIndexed { index, _ -> !isSet(words, index) })

            val stride = if (crashPoint == CrashPoint.None) batchSize else 1

            // One unit for the whole undo, as a rollback's journal run is: a failure after the first batch
            // left the ledger half-undone while the world was put back untouched.
            if (crashPoint == CrashPoint.None) {
                executor.atomically { runSteps(lease, steps, words, n, stride, crashPoint, job) }
            } else {
                runSteps(lease, steps, words, n, stride, crashPoint, job)
            }

            InvolutionOutcome.Undone(steps)
        }
    }

    private suspend fun runSteps(
        lease: LotLease,
        steps: List<InvolutionStep>,
        words: LongArray,
        n: Int,
        stride: Int,
        crashPoint: CrashPoint,
        job: RollbackJobId,
    ) {
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
