package com.tracel.plugin.specifics.block

import com.tracel.annotations.Unstable
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Waterlogged

@Unstable
internal fun Block.holdsFreeFluid(): Boolean = fluidOf(this) != null

@Unstable
internal fun fluidOf(block: Block): Material? {
    val type = block.type
    return when (type) {
        Material.WATER, Material.BUBBLE_COLUMN, in WaterPlant.materials -> Material.WATER
        Material.LAVA -> Material.LAVA
        in WATERLOGGABLE if (block.blockData as? Waterlogged)?.isWaterlogged == true -> Material.WATER
        else -> null
    }
}

@Unstable
internal fun warmFluidShapes() {
    WATERLOGGABLE.size
}

private val WATERLOGGABLE: Set<Material> by lazy {
    Material.entries.filterTo(HashSet()) { material ->
        !material.isLegacy && material.isBlock && runCatching { material.createBlockData() is Waterlogged }.getOrDefault(
            false
        )
    }
}
