package com.tracel.storage.ports.log

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.recordBytes
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.util.eachRow

/** Which log records a rollback took back, and when. */
class RolledBack(private val storage: TracelStorage) {
    /** Notes that job [job] took back [seqs] at [millis]. */
    suspend fun mark(job: Long, seqs: LongArray, millis: Long) {
        var from = 0
        while (from < seqs.size) {
            val to = minOf(seqs.size, from + CHUNK)
            storage.write {
                for (i in from until to) {
                    put(Keys.rolled(seqs[i]), recordBytes(Long.SIZE_BYTES * 2) {
                        putI64(0, job)
                        putI64(8, millis)
                    })
                    put(Keys.rolledJob(job, seqs[i]), ByteArray(0))
                }
            }
            from = to
        }
    }

    /** Forgets what job [job] took back: its rollback was undone. */
    suspend fun restore(job: Long) {
        do {
            val done = storage.write {
                val seqs = ArrayList<Long>(CHUNK)
                eachRow(Keys.rolledJobPrefix(job)) { cursor ->
                    if (seqs.size < CHUNK) seqs += KeyReader.u64(cursor.key(), 9)
                }
                for (seq in seqs) {
                    val own = get(Keys.rolled(seq))?.let { it.i64(0) == job } == true
                    if (own) delete(Keys.rolled(seq))
                    delete(Keys.rolledJob(job, seq))
                }
                seqs.size < CHUNK
            }
        } while (!done)
    }

    /** When each of [seqs] was taken back, for those that were. */
    suspend fun of(seqs: Collection<Long>): Map<Long, Long> {
        if (seqs.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<Long, Long>()
            for (seq in seqs) {
                val row = get(Keys.rolled(seq)) ?: continue
                out[seq] = row.i64(8)
            }
            out
        }
    }

    private companion object {
        const val CHUNK = 5_000
    }
}
