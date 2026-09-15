package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.isFluid
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Levelled

// TODO: rewrite

@Unstable
internal fun isFlowingLiquid(data: BlockData): Boolean {
    if (!isFluid(data)) return false
    if (data is Levelled) return data.level != 0
    return !data.asString.contains("level=0")
}

@Unstable
internal fun Block.holdsFreeFluid(): Boolean {
    val type = type
    return type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN
}

@Unstable
internal val CARDINAL: List<BlockFace> = BlockFace.entries.filter { it.isCartesian }
