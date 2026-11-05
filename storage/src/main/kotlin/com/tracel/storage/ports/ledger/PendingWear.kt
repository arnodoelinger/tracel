package com.tracel.storage.ports.ledger

import com.tracel.model.item.ItemKey
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.records.recordBytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.Bytes.writeBytes
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.util.eachRow
import java.util.*
import com.tracel.engine.ledger.PendingWear as PendingWearPort

/**
 * Pending wear. One row per stack, under the player and an ID that only grows,
 * so a claim reads them back in the order kept.
 */
class PendingWear(
    private val storage: TracelStorage,
    private val counters: Counters,
) : PendingWearPort {
    override suspend fun keepAll(player: UUID, stacks: Map<ItemKey, List<ByteArray>>) {
        val count = stacks.values.sumOf { it.size }
        if (count == 0) return
        val ids = LongArray(count) { counters.nextPendingDeliveryId() }
        storage.write {
            var at = 0
            for ((itemKey, kept) in stacks) {
                val keyId = storage.interning.internItemKey(this, itemKey)
                for (bytes in kept) {
                    put(Tag.PENDING_WEAR[player, ids[at++]], recordBytes(KEY_ID + bytes.size) {
                        putI32(0, keyId)
                        writeBytes(KEY_ID.toLong(), bytes)
                    })
                }
            }
        }
    }

    override suspend fun claimFor(player: UUID): Map<ItemKey, List<ByteArray>> = storage.write {
        val out = LinkedHashMap<ItemKey, MutableList<ByteArray>>()
        val keys = ArrayList<ByteArray>()
        eachRow(Tag.PENDING_WEAR[player]) { cursor ->
            val value = cursor.value()
            val itemKey = storage.interning.resolveItemKey(this, value.i32(0))
            out.getOrPut(itemKey, ::ArrayList) += value.readBytes(KEY_ID.toLong(), value.byteSize().toInt() - KEY_ID)
            keys += cursor.key()
        }
        keys.forEach(::delete)
        out
    }

    private companion object {
        const val KEY_ID = 4
    }
}
