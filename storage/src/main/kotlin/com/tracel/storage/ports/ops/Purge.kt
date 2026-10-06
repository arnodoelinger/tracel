package com.tracel.storage.ports.ops

import com.tracel.storage.StorageUnit
import com.tracel.engine.store.PurgeSummary
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.util.eachRow
import com.tracel.storage.codec.records.Lot as LotRecord

/** Deletes the `Tracel`'s history. */
suspend fun purgeAll(storage: TracelStorage): PurgeSummary {
    return storage.alone {
        val kept = ArrayList<Pair<ByteArray, ByteArray>>()
        var total = 0L
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        val bytes = storage.engine.stats().liveBytes
        StorageUnit(storage.engine.snapshot(), MutationBatch()).use { unit ->
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
            val seqKey = Keys.counter(Counters.SEQ)
            val used = unit.get(seqKey)?.let(Records::asLong)
            if (used != null && used < Counters.SEQ_BASE) {
                val importKey = Keys.counter(Counters.IMPORT_SEQ)
                val imported = unit.get(importKey)?.let(Records::asLong) ?: 1L
                kept.removeAll { (key, _) -> key.contentEquals(seqKey) || key.contentEquals(importKey) }
                kept += seqKey to Records.long(Counters.SEQ_BASE)
                kept += importKey to Records.long(maxOf(imported, used))
            }
        }
        storage.engine.wipe(MutationBatch().apply { for ((key, value) in kept) put(key, value) })
        storage.reloadInterning()
        PurgeSummary(
            total - kept.size,
            bytes,
            oldest.takeIf { it != Long.MAX_VALUE },
            newest.takeIf { it != Long.MIN_VALUE })
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
