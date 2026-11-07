package com.tracel.engine.world

import com.tracel.engine.store.KeyedTable
import com.tracel.model.world.BlockPos

/** What a block remembers, keyed by where it stands. */
public typealias BlockNotes = KeyedTable<BlockPos, Int>
