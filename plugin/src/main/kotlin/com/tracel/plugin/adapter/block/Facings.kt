package com.tracel.plugin.adapter.block

import com.tracel.plugin.util.geometry.Facing
import org.bukkit.block.BlockFace

/** This side of a block as `Bukkit` names it. */
internal fun Facing.toBlockFace(): BlockFace = when (this) {
    Facing.NORTH -> BlockFace.NORTH
    Facing.EAST -> BlockFace.EAST
    Facing.SOUTH -> BlockFace.SOUTH
    Facing.WEST -> BlockFace.WEST
    Facing.UP -> BlockFace.UP
    Facing.DOWN -> BlockFace.DOWN
}
