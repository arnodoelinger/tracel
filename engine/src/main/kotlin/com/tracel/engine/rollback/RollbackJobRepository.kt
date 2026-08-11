package com.tracel.engine.rollback

import com.tracel.model.id.RollbackJobId

/** Storage port for [RollbackJobRecord]s — what [InvolutionPlanner] needs to reverse a job. */
public interface RollbackJobRepository {
    public fun save(record: RollbackJobRecord)

    public fun find(id: RollbackJobId): RollbackJobRecord?
}
