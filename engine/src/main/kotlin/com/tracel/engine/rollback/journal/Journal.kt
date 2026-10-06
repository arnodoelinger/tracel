package com.tracel.engine.rollback.journal

import com.tracel.model.rollback.RollbackJobId

/**
 * Durable record of which steps of a rollback job have finished.
 *
 * After a crash, what [isCompleted] and [completed] say is what lets a resumed job skip everything that already
 * happened, instead of running it again and duplicating the result. Step [count] is the job's release step: the one
 * after the last.
 */
public interface Journal {
    /** Records that step [stepIndex] of [job] has finished. */
    public suspend fun markCompleted(job: RollbackJobId, stepIndex: Int)

    /** Records that steps [from] up to, but not including, [until] have finished. */
    public suspend fun markCompleted(job: RollbackJobId, from: Int, until: Int) {
        for (index in from until until) markCompleted(job, index)
    }

    /** Whether step [stepIndex] of [job] has finished. */
    public suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean

    /** The finished steps of [job] among the first [count], counted from step 0. */
    public suspend fun completed(job: RollbackJobId, count: Int): Set<Int>
}
