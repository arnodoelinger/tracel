package com.tracel.storage.ports.job

import com.tracel.model.id.RollbackJobId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment
import com.tracel.engine.rollback.journal.Journal as JournalPort

/** Which steps of which job have already run. */
class Journal(private val storage: TracelStorage, private val kind: Byte) : JournalPort {
    override suspend fun markCompleted(job: RollbackJobId, stepIndex: Int) {
        storage.write { put(Keys.applied(kind, job.raw, stepIndex), EMPTY) }
    }

    override suspend fun markCompleted(job: RollbackJobId, from: Int, until: Int) {
        if (until <= from) return
        if (until == from + 1) return markCompleted(job, from)
        storage.write { put(Keys.applied(kind, job.raw, from), Records.int(until)) }
    }

    override suspend fun isCompleted(job: RollbackJobId, stepIndex: Int): Boolean = storage.read {
        var hit = false
        eachRow(Keys.appliedPrefix(kind, job.raw)) { cursor ->
            val first = KeyReader.u32(cursor.key(), 10)
            if (first <= stepIndex && stepIndex < untilOf(first, cursor.value())) hit = true
        }
        hit
    }

    override suspend fun completed(job: RollbackJobId, count: Int): Set<Int> = storage.read {
        val out = HashSet<Int>()
        eachRow(Keys.appliedPrefix(kind, job.raw)) { cursor ->
            val first = KeyReader.u32(cursor.key(), 10)
            val until = minOf(untilOf(first, cursor.value()), count)
            for (index in first until until) out += index
        }
        out
    }

    private fun untilOf(first: Int, value: MemorySegment): Int =
        if (value.byteSize() == 0L) first + 1 else Records.asInt(value)

    companion object {
        fun forRollback(storage: TracelStorage): Journal = Journal(storage, Keys.PROGRESS_ROLLBACK)

        fun forInvolution(storage: TracelStorage): Journal = Journal(storage, Keys.PROGRESS_INVOLUTION)

        private val EMPTY = ByteArray(0)
    }
}
