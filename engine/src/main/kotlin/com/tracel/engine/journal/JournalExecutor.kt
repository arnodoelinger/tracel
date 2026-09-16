package com.tracel.engine.journal

import com.tracel.annotations.Journaled
import com.tracel.annotations.RequiresLease
import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ownership.LotLease
import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * Runs a [RollbackPlan] in batches and records completed steps in the [Journal].
 *
 * Completed steps are skipped when a job resumes after a crash. The final journal entry marks
 * the rollback as released.
 */
@RunsOn(ThreadContext.STORAGE)
public class JournalExecutor(
    private val executor: RollbackExecutor,
    private val journal: Journal,
    private val leases: LotLeaseRegistry,
    private val nextTxnId: suspend () -> TxnId,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    @Journaled
    @RequiresLease
    public suspend fun execute(
        lease: LotLease,
        plan: RollbackPlan,
        target: RollbackTarget,
        crashPoint: CrashPoint = CrashPoint.None,
    ) {
        require(lease.lotIds.containsAll(plan.touchedLots)) {
            "lease held by job ${lease.job} does not cover every lot this plan touches"
        }
        val job = lease.job

        // The lease comes back on every path a caller can recover from. A step
        // that threw used to leave the lots reserved to a job that was no longer
        // running, and nothing could touch them again until a restart.
        leases.holdingFor(job) {
            val n = plan.steps.size
            val done = journal.completed(job, n + 1)
            if (done.size == n + 1) return@holdingFor

            val words = LongArray((n + 64) ushr 6)
            for (index in done) {
                if (index in 0..n) setBit(words, index)
            }

            // A crash point is asking to be interrupted between two specific steps, which a batch
            // would swallow.
            if (crashPoint != CrashPoint.None) {
                runOneByOne(job, plan, target, crashPoint, words, n, n)
                return@holdingFor
            }

            val direct = done.isEmpty()
            val stride = batchSize

            // One unit for every batch: a capture landing between two of them strands a half-applied job
            executor.atomically {
                var from = 0
                while (from < n) {
                    val rawEnd = from + stride
                    val until = if (rawEnd < n) rawEnd else n
                    var index = from
                    while (index < until && isSet(words, index)) index++
                    val last = until == n
                    val needRelease = last && !isSet(words, n)
                    if (index == until && !needRelease) {
                        from = until
                        continue
                    }

                    executor.atomically {
                        if (index < until) {
                            val slice = pendingSlice(plan.steps, words, from, until)
                            executor.applyAll(
                                job,
                                slice,
                                nextTxnId(),
                                nextTxn = nextTxnId,
                                plan = if (direct) plan else null,
                                target = if (direct) target else null,
                            )
                            var marked = from
                            while (marked < until) {
                                if (!isSet(words, marked)) {
                                    journal.markCompleted(job, marked)
                                    setBit(words, marked)
                                }
                                marked++
                            }
                        }
                        if (needRelease) {
                            if (!direct) executor.release(job, plan, target, nextTxnId())
                            journal.markCompleted(job, n)
                            setBit(words, n)
                        }
                    }
                    from = until
                }
            }

            if (n == 0 && !isSet(words, n)) {
                executor.atomically {
                    crashPoint.checkBefore(n)
                    executor.release(job, plan, target, nextTxnId())
                    journal.markCompleted(job, n)
                }
            }
        }
    }

    /**
     * Executes the plan one step at a time so [crashPoint] can interrupt
     * between steps.
     */
    private suspend fun runOneByOne(
        job: RollbackJobId,
        plan: RollbackPlan,
        target: RollbackTarget,
        crashPoint: CrashPoint,
        words: LongArray,
        n: Int,
        releaseIndex: Int,
    ) {
        var index = 0
        while (index < n) {
            if (!isSet(words, index)) {
                executor.atomically {
                    crashPoint.checkBefore(index)
                    executor.apply(job, plan.steps[index], nextTxnId())
                    journal.markCompleted(job, index)
                    setBit(words, index)
                }
            }
            index++
        }
        if (!isSet(words, releaseIndex)) {
            executor.atomically {
                crashPoint.checkBefore(releaseIndex)
                executor.release(job, plan, target, nextTxnId())
                journal.markCompleted(job, releaseIndex)
            }
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

        private fun pendingSlice(
            steps: List<RollbackStep>,
            words: LongArray,
            from: Int,
            until: Int,
        ): List<RollbackStep> {
            var pending = 0
            var i = from
            while (i < until) {
                if (!isSet(words, i)) pending++
                i++
            }
            if (pending == 0) return emptyList()
            if (pending == until - from) return steps.subList(from, until)
            return SparseSteps(steps, words, from, until, pending)
        }
    }
}

/**
 * A lazy view of the unfinished steps in a range.
 *
 * Avoids allocating a new list when a batch contains only a few unfinished steps.
 */
private class SparseSteps(
    private val steps: List<RollbackStep>,
    private val words: LongArray,
    private val from: Int,
    private val until: Int,
    override val size: Int,
) : List<RollbackStep> {
    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: RollbackStep): Boolean {
        for (step in this) if (step == element) return true
        return false
    }

    override fun containsAll(elements: Collection<RollbackStep>): Boolean {
        for (element in elements) if (!contains(element)) return false
        return true
    }

    override fun get(index: Int): RollbackStep {
        if (index !in indices) throw IndexOutOfBoundsException(index)
        var seen = 0
        var i = from
        while (i < until) {
            if (words[i ushr 6] and (1L shl (i and 63)) == 0L) {
                if (seen == index) return steps[i]
                seen++
            }
            i++
        }
        throw IndexOutOfBoundsException(index)
    }

    override fun indexOf(element: RollbackStep): Int {
        for ((seen, step) in this.withIndex()) {
            if (step == element) return seen
        }
        return -1
    }

    override fun lastIndexOf(element: RollbackStep): Int {
        var last = -1
        for ((seen, step) in this.withIndex()) {
            if (step == element) last = seen
        }
        return last
    }

    override fun iterator(): Iterator<RollbackStep> = object : Iterator<RollbackStep> {
        private var i = from

        init {
            skipDone()
        }

        private fun skipDone() {
            while (i < until && words[i ushr 6] and (1L shl (i and 63)) != 0L) i++
        }

        override fun hasNext(): Boolean = i < until

        override fun next(): RollbackStep {
            if (!hasNext()) throw NoSuchElementException()
            val step = steps[i++]
            skipDone()
            return step
        }
    }

    override fun listIterator(): ListIterator<RollbackStep> = snapshot().listIterator()

    override fun listIterator(index: Int): ListIterator<RollbackStep> = snapshot().listIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<RollbackStep> = snapshot().subList(fromIndex, toIndex)

    private fun snapshot(): ArrayList<RollbackStep> {
        val out = ArrayList<RollbackStep>(size)
        for (step in this) out.add(step)
        return out
    }
}
