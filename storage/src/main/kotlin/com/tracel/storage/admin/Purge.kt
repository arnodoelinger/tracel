package com.tracel.storage.admin

import com.tracel.engine.store.PurgeSummary
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.*
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.table.SegmentClassifier
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.util.eachRow
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import java.lang.foreign.ValueLayout

/** Deletes the `Tracel`'s history. */
suspend fun purgeAll(storage: TracelStorage): PurgeSummary {
    return storage.alone {
        val kept = ArrayList<Pair<ByteArray, ByteArray>>()
        var total = 0L
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        val bytes = storage.engine.stats().liveBytes
        StorageUnit.open(storage.engine).use { unit ->
            unit.eachRow(ByteArray(0)) { cursor ->
                total++
                val key = cursor.key()
                if (key.size == TIME_KEY_SIZE && key[0] == Tag.TIME.byte) {
                    val at = Keys.invert(KeyReader.u64(key, 1))
                    if (at < oldest) oldest = at
                    if (at > newest) newest = at
                }
            }
            for (family in Tag.KEEPS_NUMBERING) {
                unit.eachRow(family.prefix) { cursor ->
                    val value = cursor.value()
                    kept += cursor.key() to value.readBytes(0, value.byteSize().toInt())
                }
            }
            for (family in listOf(Tag.CHRONO_CAPSULE, Tag.CHRONO_FRAGMENT, Tag.CHRONO_TRACK, Tag.CHRONO_TIME)) {
                unit.eachRow(family.prefix) { cursor ->
                    val value = cursor.value()
                    if (value.byteSize() == 0L) return@eachRow
                    val first = value.get(ValueLayout.JAVA_BYTE, 0).toInt() and 0xFF
                    if (History.categoryOf(family.byte.toInt() and 0xFF, first) == SegmentClassifier.STATE) {
                        kept += cursor.key() to value.readBytes(0, value.byteSize().toInt())
                    }
                }
            }
            val seqKey = Tag.COUNTER[StoredCounters.SEQ]
            val used = unit.get(seqKey)?.let(Scalar::asLong)
            if (used != null && used < StoredCounters.SEQ_BASE) {
                val importKey = Tag.COUNTER[StoredCounters.IMPORT_SEQ]
                val imported = unit.get(importKey)?.let(Scalar::asLong) ?: 1L
                kept.removeAll { (key, _) -> key.contentEquals(seqKey) || key.contentEquals(importKey) }
                kept += seqKey to Scalar.long(StoredCounters.SEQ_BASE)
                kept += importKey to Scalar.long(maxOf(imported, used))
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
        eachRow(Tag.TOTAL.prefix) { cursor -> keys += cursor.key() }
        keys.forEach(::delete)

        val totals = Long2LongOpenHashMap()
        eachRow(Tag.PACK_ITEM.prefix) { cursor ->
            val key = cursor.key()
            val itemKeyId = KeyReader.u32(key, 1)
            val holderId = KeyReader.u32(key, 5)
            val packed = (holderId.toLong() shl 32) or (itemKeyId.toLong() and 0xFFFFFFFFL) // -> 64
            totals.addTo(packed, PackSum.sum[cursor.value()])
        }
        for (entry in totals.long2LongEntrySet()) {
            val packed = entry.longKey
            val total = entry.longValue
            if (total == 0L) continue
            put(
                Tag.TOTAL[(packed ushr 32).toInt(), packed.toInt()],
                Scalar.long(total),
            )
        }
    }
}
