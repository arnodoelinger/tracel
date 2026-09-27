package com.tracel.engine.rollback.job

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId

/** In-memory [RollbackJobRepository]. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryRollbackJobRepository : RollbackJobRepository {
    private val writer = SingleWriterGuard()
    private val records = mutableMapOf<RollbackJobId, RollbackJobRecord>()
    private val stack = ArrayDeque<RollbackJobId>()

    override suspend fun save(record: RollbackJobRecord) {
        writer.checkIn()
        records[record.id] = record
        if (record.id !in stack) stack.addLast(record.id)
        val own = stack.filter { records[it]?.by == record.by }
        for (evicted in own.take(maxOf(0, own.size - RollbackJobRepository.UNDO_DEPTH))) {
            stack.remove(evicted)
            records.remove(evicted)
        }
    }

    @Reads
    override suspend fun find(id: RollbackJobId): RollbackJobRecord? = records[id]

    @Reads
    override suspend fun undoable(limit: Int): List<RollbackJobId> = stack.reversed().take(limit)

    @Reads
    override suspend fun undoableBy(by: HolderId?, limit: Int): List<RollbackJobId> =
        stack.reversed().filter { records[it]?.by == by }.take(limit)

    @Reads
    override suspend fun isUndoable(id: RollbackJobId): Boolean = id in stack

    override suspend fun markUndone(id: RollbackJobId) {
        writer.checkIn()
        stack.remove(id)
        records.remove(id)
    }
}
