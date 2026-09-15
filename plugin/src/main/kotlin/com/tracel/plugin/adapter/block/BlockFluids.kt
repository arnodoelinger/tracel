package com.tracel.plugin.adapter.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockShape
import org.bukkit.Material
import org.bukkit.block.data.BlockData

/** Water, lava, bubble column, etc. */
@Unstable
internal fun isFluid(data: BlockData): Boolean {
    val material = data.material
    return material == Material.WATER || material == Material.LAVA || material == Material.BUBBLE_COLUMN
}

/** Whether it has fluid shape. */
@Unstable
internal fun isFluidShape(shape: BlockShape): Boolean {
    val data = BlockDataCache.of(shape.data)
    if (data != null) return isFluid(data)
    val material = shape.data.value.substringBefore('[').substringAfter(':')
    return material == "water" || material == "lava" || material == "bubble_column"
}
