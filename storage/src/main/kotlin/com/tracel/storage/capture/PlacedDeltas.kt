package com.tracel.storage.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos

/** Item deltas with the place they happened, which a ring slot has no room for. Parked beside the ring, in turn. */
class PlacedDeltas(
    val deltas: List<InventoryDelta>,
    val epochMillis: Long,
    val cause: CauseKind,
    val causedBy: HolderId?,
    val at: BlockPos,
)
