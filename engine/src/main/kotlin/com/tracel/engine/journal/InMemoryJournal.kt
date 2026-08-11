package com.tracel.engine.journal

import com.tracel.model.id.RollbackJobId

/**
 * In-memory [Journal].
 */
public class InMemoryJournal : Journal {
    private val completed = mutableMapOf<RollbackJobId, MutableSet<Int>>()

    override fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        completed.getOrPut(job) { mutableSetOf() }.add(stepIndex)
    }

    override fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean =
        completed[job]?.contains(stepIndex) == true
}
