package com.tracel.storage.ports

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys

/** Every key family `Tracel` owns. Deleting all of them is deleting the database. */
private val ALL_FAMILIES = byteArrayOf(
    Keys.TXN, Keys.TXN_BY_ID, Keys.ACTOR, Keys.ITEM, Keys.TIME, Keys.SPATIAL,
    Keys.LOT, Keys.PLACE, Keys.PLACE_REV, Keys.PLACE_ITEM, Keys.TOTAL,
    Keys.EDGE_FROM, Keys.EDGE_INTO, Keys.LEASE, Keys.LEASE_JOB,
    Keys.RB_STEP, Keys.RB_JOB, Keys.APPLIED, Keys.PENDING, Keys.COUNTER,
    Keys.INTERN_FORWARD, Keys.INTERN_REVERSE,
)

/** Deletes everything, as one atomic batch. */
suspend fun purgeAll(storage: TracelStorage, counters: Counters) {
    storage.write {
        for (family in ALL_FAMILIES) {
            val keys = ArrayList<ByteArray>()
            scan(Keys.tagPrefix(family)).use { cursor ->
                while (cursor.next()) keys += cursor.key()
            }
            keys.forEach(::delete)
        }
    }
    storage.interning.forget()
    counters.forget()
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
                val packed = (holderId.toLong() shl 32) or (itemKeyId.toLong() and 0xFFFFFFFFL)
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
