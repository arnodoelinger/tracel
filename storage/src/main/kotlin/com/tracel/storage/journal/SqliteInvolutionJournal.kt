package com.tracel.storage.journal

import com.tracel.engine.journal.Journal
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.schema.InvolutionProgressTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `SQLite`-backed [Journal] for undo progress.
 */
class SqliteInvolutionJournal(private val db: Database) : Journal {
    private val writer = SingleWriterGuard()

    override fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        writer.checkIn()
        transaction(db) {
            InvolutionProgressTable.insertIgnore {
                it[jobId] = job.raw
                it[InvolutionProgressTable.stepIndex] = stepIndex
            }
        }
    }

    override fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean = transaction(db) {
        InvolutionProgressTable.selectAll()
            .where { (InvolutionProgressTable.jobId eq job.raw) and (InvolutionProgressTable.stepIndex eq stepIndex) }
            .limit(1)
            .any()
    }
}
