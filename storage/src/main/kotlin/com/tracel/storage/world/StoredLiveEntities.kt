package com.tracel.storage.world

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.table.KeyCodec
import com.tracel.storage.table.StoredKeySet
import java.util.UUID

/** One empty row per entity that is still in the world. */
class StoredLiveEntities(storage: TracelStorage) : StoredKeySet<UUID>(storage, Tag.LIVE_ENTITY, KeyCodec.uuid)
