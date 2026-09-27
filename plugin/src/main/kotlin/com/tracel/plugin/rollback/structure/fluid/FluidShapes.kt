package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.Waterlogged

// TODO: rewrite

@Unstable
internal fun isFlowingLiquid(data: BlockData): Boolean {
    val material = data.material
    return !(material != Material.WATER && material != Material.LAVA) && ((data as? Levelled)?.level ?: 0) != 0
}

@Unstable
internal fun Block.holdsFreeFluid(): Boolean = fluidOf(this) != null

@Unstable
internal fun fluidOf(block: Block): Material? = when (val type = block.type) {
    Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
    Material.SEAGRASS, Material.TALL_SEAGRASS -> Material.WATER

    Material.LAVA -> Material.LAVA
    else -> if (type in WATERLOGGABLE && (block.blockData as? Waterlogged)?.isWaterlogged == true) Material.WATER else null
}

@Unstable
internal fun levelOf(block: Block): Int = when (block.type) {
    Material.WATER, Material.LAVA -> (block.blockData as? Levelled)?.level ?: 0
    else -> 0
}

@Unstable
internal fun warmFluidShapes() {
    WATERLOGGABLE.size
}

@Unstable
internal val CARDINAL: List<BlockFace> = BlockFace.entries.filter { it.isCartesian }

@Unstable
internal val HORIZONTAL: List<BlockFace> = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

private val WATERLOGGABLE: Set<Material> by lazy {
    Material.entries.filterTo(HashSet()) { material ->
        !material.isLegacy && material.isBlock && runCatching { material.createBlockData() is Waterlogged }.getOrDefault(
            false
        )
    }
}
