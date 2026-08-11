package com.tracel.engine.rollback

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.RollbackJobId

/** In-memory [RollbackJobRepository]. */
public class InMemoryRollbackJobRepository : RollbackJobRepository {
    private val writer = SingleWriterGuard()
    private val records = mutableMapOf<RollbackJobId, RollbackJobRecord>()

    override fun save(record: RollbackJobRecord) {
        writer.checkIn()
        records[record.id] = record
    }

    override fun find(id: RollbackJobId): RollbackJobRecord? = records[id]
}
