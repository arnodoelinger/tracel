package com.tracel.storage.ledger

import com.tracel.engine.ledger.PendingWear
import com.tracel.model.item.ItemKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.admin.StoredCounters
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.recordBytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.Bytes.writeBytes
import com.tracel.storage.table.ClaimQueue
import com.tracel.storage.table.KeyCodec
import com.tracel.storage.table.ValueCodec
import java.lang.foreign.MemorySegment
import java.util.UUID

/**
 * Pending wear. One row per stack, under the player and an ID that only grows,
 * so a claim reads them back in the order kept.
 */
class StoredPendingWear(storage: TracelStorage, counters: StoredCounters) : PendingWear {
    private val queue = ClaimQueue(storage, Tag.PENDING_WEAR, KeyCodec.uuid, Stack, counters::nextPendingDeliveryId)

    override suspend fun keepAll(player: UUID, stacks: Map<ItemKey, List<ByteArray>>) =
        queue.addAll(player, stacks.flatMap { (itemKey, kept) -> kept.map { itemKey to it } })

    override suspend fun claimFor(player: UUID): Map<ItemKey, List<ByteArray>> {
        val out = LinkedHashMap<ItemKey, MutableList<ByteArray>>()
        for ((_, stack) in queue.claim(player)) out.getOrPut(stack.first, ::ArrayList) += stack.second
        return out
    }

    private object Stack : ValueCodec<Pair<ItemKey, ByteArray>> {
        private const val KEY_ID = 4

        override fun write(storage: TracelStorage, unit: StorageUnit, value: Pair<ItemKey, ByteArray>): ByteArray {
            val keyId = storage.interning.internItemKey(unit, value.first)
            return recordBytes(KEY_ID + value.second.size) {
                putI32(0, keyId)
                writeBytes(KEY_ID.toLong(), value.second)
            }
        }

        override fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment): Pair<ItemKey, ByteArray> =
            storage.interning.resolveItemKey(unit, value.i32(0)) to
                value.readBytes(KEY_ID.toLong(), value.byteSize().toInt() - KEY_ID)
    }
}
