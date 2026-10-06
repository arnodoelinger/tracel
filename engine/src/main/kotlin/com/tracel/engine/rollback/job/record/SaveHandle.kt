package com.tracel.engine.rollback.job.record

/**
 * A persist that [RollbackJobRepository.begin] started and [RollbackJobRepository.finish] has still to complete.
 *
 * [record] is what was begun. [fromRun] is how many runs of it were already written, and so where [finish] carries on.
 */
public class SaveHandle(public val record: RollbackJobRecord, public val fromRun: Int)
