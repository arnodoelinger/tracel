package com.tracel.storage.journal

import com.tracel.engine.journal.Journal
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.Storage
import com.tracel.storage.schema.InvolutionProgressTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * `SQLite`-backed [Journal] for undo progress.
 */
class SqliteInvolutionJournal(private val storage: Storage) : Journal {
    override suspend fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        storage.write {
            InvolutionProgressTable.insertIgnore {
                it[jobId] = job.raw
                it[InvolutionProgressTable.stepIndex] = stepIndex
            }
        }
    }

    override suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean = storage.read {
        InvolutionProgressTable.selectAll()
            .where { (InvolutionProgressTable.jobId eq job.raw) and (InvolutionProgressTable.stepIndex eq stepIndex) }
            .limit(1)
            .any()
    }
}
