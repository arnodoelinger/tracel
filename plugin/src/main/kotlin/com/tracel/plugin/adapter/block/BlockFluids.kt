package com.tracel.plugin.adapter.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.specifics.block.isFluidBlock
import com.tracel.plugin.specifics.block.isFluidId
import org.bukkit.block.data.BlockData

/** Water, lava, bubble column, etc. */
@Unstable
internal fun isFluid(data: BlockData): Boolean = data.material.isFluidBlock()

/** Whether it has fluid shape. */
@Unstable
internal fun isFluidShape(shape: BlockShape): Boolean {
    val data = BlockDataCache.of(shape.data)
    if (data != null) return isFluid(data)
    return isFluidId(shape.data.value.substringBefore('[').substringAfter(':'))
}
