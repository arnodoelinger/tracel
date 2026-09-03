package com.tracel.storage.ports

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import java.lang.foreign.MemorySegment

/** Deletes the `Tracel`'s history. */
suspend fun purgeAll(storage: TracelStorage) {
    val kept = ArrayList<Pair<ByteArray, ByteArray>>()
    storage.read {
        for (family in Keys.KEEPS_ITS_NUMBERING) {
            scan(Keys.tagPrefix(family)).use { cursor ->
                while (cursor.next()) {
                    val value = cursor.value()
                    val bytes = ByteArray(value.byteSize().toInt())
                    MemorySegment.ofArray(bytes).copyFrom(value.asSlice(0, bytes.size.toLong()))
                    kept += cursor.key() to bytes
                }
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
// TODO: refactor
suspend fun rebuildTotals(storage: TracelStorage) {
    storage.write {
        val keys = ArrayList<ByteArray>()
        scan(Keys.tagPrefix(Keys.TOTAL)).use { cursor ->
            while (cursor.next()) keys += cursor.key()
        }
        keys.forEach(::delete)

        val totals = HashMap<Long, Long>()
        scan(Keys.tagPrefix(Keys.PLACE)).use { cursor ->
            while (cursor.next()) {
                val key = cursor.key()
                val holderId = com.tracel.storage.codec.KeyReader.u32(key, 1)
                val itemKeyId = com.tracel.storage.codec.KeyReader.u32(key, 5)
                val packed = (holderId.toLong() shl 32) or (itemKeyId.toLong() and 0xFFFFFFFFL) // -> 64
                totals.merge(packed, com.tracel.storage.codec.Records.placementRemaining(cursor.value()), Long::plus)
            }
        }
        for ((packed, total) in totals) {
            if (total == 0L) continue
            put(
                Keys.total((packed ushr 32).toInt(), packed.toInt()),
                com.tracel.storage.codec.Records.long(total),
            )
        }
    }
}
