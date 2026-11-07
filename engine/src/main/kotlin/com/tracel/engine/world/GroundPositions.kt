package com.tracel.engine.world

import com.tracel.engine.store.KeyedTable
import com.tracel.model.holder.HolderId

/** Where a dropped item last touched the ground, so a rollback can drop it back in the right place. */
public typealias GroundPositions = KeyedTable<HolderId.ItemEntity, HolderId.Block>
