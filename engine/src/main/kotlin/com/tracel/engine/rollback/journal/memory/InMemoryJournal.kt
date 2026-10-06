package com.tracel.engine.rollback.journal.memory

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.platform.concurrency.SingleWriterGuard
import com.tracel.model.id.RollbackJobId
import com.tracel.engine.rollback.journal.Journal

/** In-memory [Journal], the reference the stored one is checked against. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryJournal : Journal {
    private val writer = SingleWriterGuard()
    private val completed = mutableMapOf<RollbackJobId, MutableSet<Int>>()

    override suspend fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        writer.checkIn()
        completed.getOrPut(job) { mutableSetOf() }.add(stepIndex)
    }

    @Reads
    override suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean =
        completed[job]?.contains(stepIndex) == true

    @Reads
    override suspend fun completed(job: RollbackJobId, count: Int): Set<Int> =
        completed[job]?.filterTo(mutableSetOf()) { it < count }.orEmpty()
}
