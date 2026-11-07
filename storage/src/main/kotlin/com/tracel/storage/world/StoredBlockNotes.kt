package com.tracel.storage.world

import com.tracel.model.world.BlockPos
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.table.KeyCodec
import com.tracel.storage.table.StoredKeyedTable
import com.tracel.storage.table.ValueCodec

/** One row per block that has a note, keyed by where it stands. */
class StoredBlockNotes(storage: TracelStorage) :
    StoredKeyedTable<BlockPos, Int>(storage, Tag.BLOCK_NOTE, KeyCodec.blockPos, ValueCodec.int)
