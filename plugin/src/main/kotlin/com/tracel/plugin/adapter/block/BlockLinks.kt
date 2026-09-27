package com.tracel.plugin.adapter.block

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.plugin.adapter.block.special.DoubleChest
import org.bukkit.block.Block

/**
 * Holder identity for a block, including the double-chest partner.
 *
 * The numerically lesser half owns the cargo account, so both halves share one
 * [HolderId.Block].
 */
fun Block.toHolderId(): HolderId.Block {
    val other = DoubleChest.partner(this)
    if (other != null && (other.x < x || other.z < z)) {
        return HolderId.Block(WorldId(world.uid), other.x, other.y, other.z)
    }
    return HolderId.Block(WorldId(world.uid), x, y, z)
}

/**
 * When this half is the owner, the partner that should absorb remaining
 * cargo after this block breaks. None if this is the lesser half.
 */
fun Block.accountMovesTo(): HolderId.Block? {
    val other = DoubleChest.partner(this) ?: return null
    if (other.x < x || other.z < z) return null
    return HolderId.Block(WorldId(other.world.uid), other.x, other.y, other.z)
}

/** Holder identity for a placed block. */
fun Block.toPlacedBlockId(): HolderId.PlacedBlock =
    HolderId.PlacedBlock(WorldId(world.uid), x, y, z)
