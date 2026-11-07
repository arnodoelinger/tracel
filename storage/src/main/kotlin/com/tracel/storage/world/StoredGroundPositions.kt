package com.tracel.storage.world

import com.tracel.model.holder.HolderId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.table.KeyCodec
import com.tracel.storage.table.StoredKeyedTable
import com.tracel.storage.table.ValueCodec

/**
 * Remembers where a dropped item last touched the ground.
 *
 * Item transactions only know the item UUID. When the item despawns, this store keeps its
 * final position so a rollback can drop it back in the right place.
 */
class StoredGroundPositions(storage: TracelStorage) :
    StoredKeyedTable<HolderId.ItemEntity, HolderId.Block>(storage, Tag.GROUND_AT, KeyCodec.holder(), ValueCodec.holder())
