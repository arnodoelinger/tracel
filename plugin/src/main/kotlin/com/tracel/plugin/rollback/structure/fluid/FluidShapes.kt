package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Waterlogged

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
internal fun warmFluidShapes() {
    WATERLOGGABLE.size
}

@Unstable
internal val CARDINAL: List<BlockFace> = BlockFace.entries.filter { it.isCartesian }

private val WATERLOGGABLE: Set<Material> by lazy {
    Material.entries.filterTo(HashSet()) { material ->
        !material.isLegacy && material.isBlock && runCatching { material.createBlockData() is Waterlogged }.getOrDefault(
            false
        )
    }
}
