package com.tracel.storage.ports.job

import com.tracel.model.id.RollbackJobId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.util.eachRow
import com.tracel.engine.journal.Journal as JournalPort

/** Which steps of which job have already run. */
class Journal(private val storage: TracelStorage, private val kind: Byte) : JournalPort {
    override suspend fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        storage.write { put(Keys.applied(kind, job.raw, stepIndex), EMPTY) }
    }

    override suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean =
        storage.read { exists(Keys.applied(kind, job.raw, stepIndex)) }

    override suspend fun completed(job: RollbackJobId, count: Int): Set<Int> = storage.read {
        val out = HashSet<Int>()
        eachRow(Keys.appliedPrefix(kind, job.raw)) { cursor ->
            val index = KeyReader.u32(cursor.key(), 10)
            if (index < count) out += index
        }
        out
    }

    companion object {
        fun forRollback(storage: TracelStorage): Journal = Journal(storage, Keys.PROGRESS_ROLLBACK)

        fun forInvolution(storage: TracelStorage): Journal = Journal(storage, Keys.PROGRESS_INVOLUTION)

        private val EMPTY = ByteArray(0)
    }
}
