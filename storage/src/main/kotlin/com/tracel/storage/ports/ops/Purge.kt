package com.tracel.storage.ports.ops

import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.util.eachRow
import com.tracel.storage.codec.records.Lot as LotRecord

/** What a purge threw away, for the line that gets printed afterward. */
data class PurgeSummary(val rows: Long, val bytes: Long, val oldest: Long? = null, val newest: Long? = null)

/** Deletes the `Tracel`'s history. */
suspend fun purgeAll(storage: TracelStorage): PurgeSummary {
    return storage.alone {
        val kept = ArrayList<Pair<ByteArray, ByteArray>>()
        var total = 0L
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        val bytes = storage.engine.stats().liveBytes
        StorageUnit(storage.engine.snapshot(), MutationBatch(), Thread.currentThread()).use { unit ->
            unit.eachRow(ByteArray(0)) { cursor ->
                total++
                val key = cursor.key()
                if (key.size == TIME_KEY_SIZE && key[0] == Keys.TIME) {
                    val at = Keys.invert(KeyReader.u64(key, 1))
                    if (at < oldest) oldest = at
                    if (at > newest) newest = at
                }
            }
            for (family in Keys.KEEPS_ITS_NUMBERING) {
                unit.eachRow(Keys.tagPrefix(family)) { cursor ->
                    val value = cursor.value()
                    kept += cursor.key() to value.readBytes(0, value.byteSize().toInt())
                }
            }
        }
        storage.engine.wipe()
        if (kept.isNotEmpty()) storage.engine.write(MutationBatch().apply {
            for ((key, value) in kept) put(
                key,
                value
            )
        }, durable = true)
        storage.reloadInterning()
        PurgeSummary(total - kept.size, bytes, oldest.takeIf { it != Long.MAX_VALUE }, newest.takeIf { it != Long.MIN_VALUE })
    }
}

/**
 * Rebuilds the running totals from the placements they summarize.
 *
 * The `total` family is a cache and nothing more, so this is the answer to any suspicion about
 * it — and the reason a bug there is a wrong number (rather than lost history).
 */
suspend fun rebuildTotals(storage: TracelStorage) {
    storage.write {
        val keys = ArrayList<ByteArray>()
        eachRow(Keys.tagPrefix(Keys.TOTAL)) { cursor -> keys += cursor.key() }
        keys.forEach(::delete)

        val totals = HashMap<Long, Long>()
        eachRow(Keys.tagPrefix(Keys.PACK_ITEM)) { cursor ->
            val key = cursor.key()
            val itemKeyId = KeyReader.u32(key, 1)
            val holderId = KeyReader.u32(key, 5)
            val packed = (holderId.toLong() shl 32) or (itemKeyId.toLong() and 0xFFFFFFFFL) // -> 64
            totals.merge(packed, LotRecord.sumOf(cursor.value()), Long::plus)
        }
        for ((packed, total) in totals) {
            if (total == 0L) continue
            put(
                Keys.total((packed ushr 32).toInt(), packed.toInt()),
                Records.long(total),
            )
        }
    }
}
