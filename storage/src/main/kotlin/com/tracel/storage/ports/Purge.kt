package com.tracel.storage.ports

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys

/**
 * Deletes the `Tracel`'s history.
 */
// TODO: refactor
suspend fun purgeAll(storage: TracelStorage) {
    storage.write {
        for (family in Keys.ALL.filterNot { it in Keys.KEEPS_ITS_NUMBERING }) {
            val keys = ArrayList<ByteArray>()
            scan(Keys.tagPrefix(family)).use { cursor ->
                while (cursor.next()) keys += cursor.key()
            }
            keys.forEach(::delete)
        }
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
