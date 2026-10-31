package com.tracel.storage.ports.world

import com.tracel.model.holder.HolderId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.records.Scalar
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.engine.world.GroundPositions as GroundPositionsPort

/**
 * Remembers where a dropped item last touched the ground.
 *
 * Item transactions only know the item UUID. When the item despawns, this store keeps its
 * final position so a rollback can drop it back in the right place.
 */
class GroundPositions(private val storage: TracelStorage) : GroundPositionsPort {
    override suspend fun rememberAll(positions: Map<HolderId.ItemEntity, HolderId.Block>) {
        if (positions.isEmpty()) return
        storage.write {
            for ((item, at) in positions) {
                val itemId = storage.interning.internHolder(this, item)
                val atId = storage.interning.internHolder(this, at)
                put(Tag.GROUND_AT[itemId], Scalar.int(atId))
            }
        }
    }

    override suspend fun find(item: HolderId.ItemEntity): HolderId.Block? = storage.read {
        val itemId = storage.interning.findHolderId(this, item) ?: return@read null
        val record = get(Tag.GROUND_AT[itemId]) ?: return@read null
        storage.interning.resolveHolder(this, record.i32(0)) as? HolderId.Block
    }
}
