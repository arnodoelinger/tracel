package com.tracel.engine.rollback

import com.tracel.model.id.RollbackJobId

/**
 * Storage port for [RollbackJobRecord]s — what [com.tracel.engine.rollback.involution.InvolutionPlanner]
 * needs to reverse a job.
 */
public interface RollbackJobRepository {
    public suspend fun save(record: RollbackJobRecord)

    public suspend fun find(id: RollbackJobId): RollbackJobRecord?
}
