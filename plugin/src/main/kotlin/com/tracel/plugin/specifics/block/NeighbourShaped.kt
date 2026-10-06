package com.tracel.plugin.specifics.block

import org.bukkit.block.Block
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Fence
import org.bukkit.block.data.type.GlassPane
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.Tripwire
import org.bukkit.block.data.type.Wall

/** Whether this block's state depends on what stands next to it: a fence connects, a chest pairs, stairs turn. */
internal fun Block.isShapedByNeighbours(): Boolean = when (blockData) {
    is Chest, is Fence, is Wall, is GlassPane, is Stairs, is RedstoneWire, is Tripwire -> true
    else -> false
}
