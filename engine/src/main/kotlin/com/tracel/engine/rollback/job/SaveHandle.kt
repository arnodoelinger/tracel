package com.tracel.engine.rollback.job

/** Incomplete persist: [RollbackJobRepository.begin] -> [RollbackJobRepository.finish]. */
public class SaveHandle(public val record: RollbackJobRecord, public val fromRun: Int)
