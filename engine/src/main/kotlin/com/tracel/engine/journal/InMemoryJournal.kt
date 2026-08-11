package com.tracel.engine.journal

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.RollbackJobId

/**
 * In-memory [Journal].
 */
public class InMemoryJournal : Journal {
    private val writer = SingleWriterGuard()
    private val completed = mutableMapOf<RollbackJobId, MutableSet<Int>>()

    override fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        writer.checkIn()
        completed.getOrPut(job) { mutableSetOf() }.add(stepIndex)
    }

    override fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean =
        completed[job]?.contains(stepIndex) == true
}
