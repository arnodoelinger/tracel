package com.tracel.storage.ports.container

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.holder.HolderId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.ContainerSlot
import com.tracel.storage.ports.ops.Counters
import com.tracel.engine.container.ContainerSlotLog as ContainerSlotLogPort

/**
 * Where a container's contents sat, slot by slot, over time.
 *
 * @see [ContainerSlotLogPort].
 */
class ContainerSlotLog(
    private val storage: TracelStorage,
    private val counters: Counters,
) : ContainerSlotLogPort {
    override suspend fun record(holder: HolderId, epochMillis: Long, slots: List<ContainerSlotEntry>) {
        val seq = counters.nextSeq().raw
        storage.write {
            val holderId = storage.interning.internHolder(this, holder)
            val packed = slots.map { entry ->
                Triple(entry.slot, storage.interning.internItemKey(this, entry.itemKey), entry.quantity)
            }
            put(Keys.cslot(holderId, seq), ContainerSlot.layout(epochMillis, packed))
        }
    }

    override suspend fun layoutAt(holder: HolderId, asOfMillis: Long, limit: Int): List<ContainerSlotEntry>? {
        if (limit <= 0) return null
        return storage.read {
            val holderId = storage.interning.findHolderId(this, holder) ?: return@read null
            var seen = 0
            var result: List<ContainerSlotEntry>? = null
            scan(Keys.cslotPrefix(holderId)).use { cursor ->
                while (cursor.next()) {
                    val record = cursor.value()
                    if (ContainerSlot.layoutEpochMillis(record) <= asOfMillis) {
                        val count = ContainerSlot.layoutCount(record)
                        result = List(count) { index ->
                            ContainerSlotEntry(
                                ContainerSlot.layoutSlot(record, index),
                                storage.interning.resolveItemKey(this, ContainerSlot.layoutItemKeyId(record, index)),
                                ContainerSlot.layoutQuantity(record, index),
                            )
                        }
                        return@use
                    }
                    if (++seen >= limit) return@use
                }
            }
            result
        }
    }
}
