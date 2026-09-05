package com.tracel.storage.ports.ops

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.util.eachRow

/** Deletes the `Tracel`'s history. */
suspend fun purgeAll(storage: TracelStorage) {
    val kept = ArrayList<Pair<ByteArray, ByteArray>>()
    storage.read {
        for (family in Keys.KEEPS_ITS_NUMBERING) {
            eachRow(Keys.tagPrefix(family)) { cursor ->
                val value = cursor.value()
                val bytes = value.readBytes(0, value.byteSize().toInt())
                kept += cursor.key() to bytes
            }
        }
    }

    storage.engine.wipe()

    if (kept.isEmpty()) return
    storage.write {
        for ((key, value) in kept) put(key, value)
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
        eachRow(Keys.tagPrefix(Keys.PLACE)) { cursor ->
            val key = cursor.key()
            val holderId = KeyReader.u32(key, 1)
            val itemKeyId = KeyReader.u32(key, 5)
            val packed = (holderId.toLong() shl 32) or (itemKeyId.toLong() and 0xFFFFFFFFL) // -> 64
            totals.merge(packed, Records.placementRemaining(cursor.value()), Long::plus)
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
