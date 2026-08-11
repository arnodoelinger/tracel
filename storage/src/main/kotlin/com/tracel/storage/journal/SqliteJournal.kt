package com.tracel.storage.journal

import com.tracel.engine.journal.Journal
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.schema.JournalProgressTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `SQLite`-backed [Journal] — surviving the process dying is the entire reason this exists,
 * so unlike [com.tracel.engine.journal.InMemoryJournal] it actually has something to prove.
 */
class SqliteJournal(private val db: Database) : Journal {
    private val writer = SingleWriterGuard()

    /**
     * Marks a step of a rollback job as completed, so that if the process dies and is restarted,
     * it will not be re-run.
     */
    override fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        writer.checkIn()
        transaction(db) {
            JournalProgressTable.insertIgnore {
                it[jobId] = job.raw
                it[JournalProgressTable.stepIndex] = stepIndex
            }
        }
    }

    /** Checks if a step of a rollback job has been marked as completed. */
    override fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean = transaction(db) {
        JournalProgressTable.selectAll()
            .where { (JournalProgressTable.jobId eq job.raw) and (JournalProgressTable.stepIndex eq stepIndex) }
            .limit(1)
            .any()
    }
}
