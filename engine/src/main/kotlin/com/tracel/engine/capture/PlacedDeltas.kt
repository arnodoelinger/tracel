package com.tracel.engine.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos

/** Item deltas with the place they happened, which a capture slot has no room for. Parked beside the queue, in turn. */
public class PlacedDeltas(
    public val deltas: List<InventoryDelta>,
    public val epochMillis: Long,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val at: BlockPos,
)
