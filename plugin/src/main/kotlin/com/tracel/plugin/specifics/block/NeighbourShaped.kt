package com.tracel.plugin.specifics.block

import org.bukkit.block.Block
import org.bukkit.block.data.type.*

/** Whether this block's state depends on what stands next to it: a fence connects, a chest pairs, stairs turn. */
internal fun Block.isShapedByNeighbours(): Boolean = when (blockData) {
    is Chest, is Fence, is Wall, is GlassPane, is Stairs, is RedstoneWire, is Tripwire -> true
    else -> false
}
