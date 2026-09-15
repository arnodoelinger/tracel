package com.tracel.plugin.rollback.material.spill

import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos

/** One overflowed stack, on the ground, waiting for the ledger to be told where it went. */
internal class Spill(val holder: HolderId, val delta: InventoryDelta, val at: BlockPos)
