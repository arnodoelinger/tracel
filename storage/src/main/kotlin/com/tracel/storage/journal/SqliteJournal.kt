package com.tracel.storage.journal

import com.tracel.engine.journal.Journal
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.Storage
import com.tracel.storage.schema.JournalProgressTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * `SQLite`-backed [Journal] — surviving the process dying is the entire reason this exists,
 * so unlike [com.tracel.engine.journal.InMemoryJournal] it actually has something to prove.
 */
class SqliteJournal(private val storage: Storage) : Journal {
    /**
     * Marks a step of a rollback job as completed, so that if the process dies and is restarted,
     * it will not be re-run.
     */
    override suspend fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        storage.write {
            JournalProgressTable.insertIgnore {
                it[jobId] = job.raw
                it[JournalProgressTable.stepIndex] = stepIndex
            }
        }
    }

    /** Checks if a step of a rollback job has been marked as completed. */
    override suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean = storage.read {
        JournalProgressTable.selectAll()
            .where { (JournalProgressTable.jobId eq job.raw) and (JournalProgressTable.stepIndex eq stepIndex) }
            .limit(1)
            .any()
    }
}
