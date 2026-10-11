package com.tracel.storage.format.migrations

import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.KeyWriter
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.RolledSet
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.format.FormatVersion
import com.tracel.storage.format.Migration
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import kotlin.collections.iterator

/**
 * 1.0 to 1.1.
 *
 * What a rollback took back, from a row for every record and a second one to find it by job, to a set for
 * every bucket of records.
 *
 * The old rows go as their records are folded in, a commit at a time, so stopping anywhere and starting over only
 * finds fewer of them.
 */
internal object RolledSets {
    val STEP = Migration(FormatVersion(1, 0), FormatVersion(1, 1), groundwork = ::fold) { }

    private const val CHUNK = 20_000
    private val ROLLED = byteArrayOf(Keys.ROLLED)
    private val ROLLED_JOB = byteArrayOf(Keys.ROLLED_JOB)

    private fun rolled(seq: Long): ByteArray = KeyWriter(9).tag(Keys.ROLLED).u64(seq).done()

    private fun rolledJob(job: Long, seq: Long): ByteArray =
        KeyWriter(17).tag(Keys.ROLLED_JOB).u64(job).u64(seq).done()

    private fun fold(engine: KeyValueEngine) {
        var from = 0L
        while (true) {
            val next = commit(engine) { unit -> foldSome(unit, from) }
            if (next < 0) break
            from = next
        }
        while (commit(engine, ::dropSome) >= 0) Unit
    }

    private fun foldSome(unit: StorageUnit, from: Long): Long {
        val seqs = LongArray(CHUNK)
        val jobs = LongArray(CHUNK)
        val times = LongArray(CHUNK)
        var count = 0
        unit.scan(ROLLED, rolled(from)).use { cursor ->
            while (count < CHUNK && cursor.next()) {
                seqs[count] = cursor.keyU64(1)
                jobs[count] = cursor.value().i64(0)
                times[count] = cursor.value().i64(8)
                count++
            }
        }

        val sets = HashMap<Pair<Long, Long>, LongArray>()
        val millis = HashMap<Pair<Long, Long>, Long>()
        for (i in 0 until count) {
            val bucket = RolledSet.bucketOf(seqs[i])
            val at = bucket to jobs[i]
            val slots = sets.getOrPut(at) { RolledSet.slots(unit.get(Keys.rolledSet(bucket, jobs[i]))) }
            RolledSet.add(slots, RolledSet.slotOf(seqs[i]))
            millis[at] = times[i]
            unit.delete(rolled(seqs[i]))
            unit.delete(rolledJob(jobs[i], seqs[i]))
        }
        for ((at, slots) in sets) {
            val (bucket, job) = at
            unit.put(Keys.rolledSet(bucket, job), RolledSet.encode(millis.getValue(at), slots))
            unit.put(Keys.rolledSetJob(job, bucket), ByteArray(0))
        }
        return if (count == CHUNK) seqs[count - 1] + 1 else -1L
    }

    private fun dropSome(unit: StorageUnit): Long {
        val keys = ArrayList<ByteArray>()
        unit.scan(ROLLED_JOB).use { cursor ->
            while (keys.size < CHUNK && cursor.next()) keys += cursor.key()
        }
        keys.forEach(unit::delete)
        return if (keys.size == CHUNK) 0L else -1L
    }

    private fun commit(engine: KeyValueEngine, work: (StorageUnit) -> Long): Long =
        StorageUnit(engine.snapshot(), MutationBatch()).use { unit ->
            val next = work(unit)
            if (!unit.batch.isEmpty()) engine.write(unit.batch, durable = true)
            next
        }
}
