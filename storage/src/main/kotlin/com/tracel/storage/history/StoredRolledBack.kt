package com.tracel.storage.history

import com.tracel.engine.log.RolledBack
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.RolledSet
import com.tracel.storage.codec.Tag
import com.tracel.storage.util.eachRow
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList

/**
 * Which log records a rollback took back, and when.
 *
 * Kept as a set for every bucket of sequence numbers a job touched, not a row for every record: a rollback of a
 * million records is a few hundred rows.
 */
class StoredRolledBack(private val storage: TracelStorage) : RolledBack {
    override suspend fun mark(job: Long, seqs: LongArray, millis: Long) {
        val sorted = seqs.sortedArray()
        var from = 0
        while (from < sorted.size) {
            from = storage.write {
                var at = from
                var buckets = 0
                while (at < sorted.size && buckets < CHUNK) {
                    val bucket = RolledSet.bucketOf(sorted[at])
                    val key = Tag.ROLLED_SET[bucket, job]
                    val slots = RolledSet.slots(get(key))
                    while (at < sorted.size && RolledSet.bucketOf(sorted[at]) == bucket) {
                        RolledSet.add(slots, RolledSet.slotOf(sorted[at++]))
                    }
                    put(key, RolledSet.encode(millis, slots))
                    put(Tag.ROLLED_SET_JOB[job, bucket], ByteArray(0))
                    buckets++
                }
                at
            }
        }
    }

    /** Forgets what job [job] took back: its rollback was undone. */
    override suspend fun restore(job: Long) {
        do {
            val done = storage.write {
                val buckets = LongArrayList(CHUNK)
                eachRow(Tag.ROLLED_SET_JOB[job]) { cursor ->
                    if (buckets.size < CHUNK) buckets.add(cursor.keyU64(9))
                }
                for (bucket in buckets.toLongArray()) {
                    delete(Tag.ROLLED_SET[bucket, job])
                    delete(Tag.ROLLED_SET_JOB[job, bucket])
                }
                buckets.size < CHUNK
            }
        } while (!done)
    }

    override suspend fun of(seqs: Collection<Long>): Map<Long, Long> {
        if (seqs.isEmpty()) return emptyMap()
        return storage.read {
            val out = Long2LongOpenHashMap().apply { defaultReturnValue(Long.MIN_VALUE) }
            for ((bucket, asked) in seqs.groupBy(RolledSet::bucketOf)) {
                eachRow(Tag.ROLLED_SET[bucket]) { cursor ->
                    val value = cursor.value()
                    val millis = RolledSet.millis(value)
                    val slots = RolledSet.slots(value)
                    for (seq in asked) {
                        if (RolledSet.has(slots, RolledSet.slotOf(seq)) && millis > out.get(seq)) out.put(seq, millis)
                    }
                }
            }
            out
        }
    }

    private companion object {
        const val CHUNK = 1_000
    }
}
