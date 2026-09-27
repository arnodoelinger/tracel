package com.tracel.engine.journal

import com.tracel.model.id.RollbackJobId

/**
 * Durable record of which steps of a rollback job have finished. On resume
 * after a crash, [isCompleted] is what lets execution skip everything that
 * already happened instead of re-running it and duplicating the result.
 */
public interface Journal {
    public suspend fun markCompleted(job: RollbackJobId, stepIndex: Int)

    /** Steps [from] until [until], exclusive. */
    public suspend fun markCompleted(job: RollbackJobId, from: Int, until: Int) {
        for (index in from until until) markCompleted(job, index)
    }

    public suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean
    public suspend fun completed(job: RollbackJobId, count: Int): Set<Int>
}
