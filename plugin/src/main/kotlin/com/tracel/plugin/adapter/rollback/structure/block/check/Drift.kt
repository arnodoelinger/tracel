package com.tracel.plugin.adapter.rollback.structure.block.check

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.specifics.block.FluidMadeBlock
import com.tracel.plugin.specifics.block.NaturalBlock
import com.tracel.plugin.specifics.block.SoilBlock
import com.tracel.plugin.specifics.block.Weathering
import com.tracel.plugin.specifics.block.poursLikeFluid
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Waterlogged

/**
 * Whether what stands here is the world moving on from [expected] by itself (fluid, fire, gravity,
 * growth, weathering, a state flip) rather than somebody's later build.
 */
@Unstable
internal fun Block.drifted(expected: BlockShape): Boolean {
    val data = blockData
    return drifted(
        type,
        isLiquid,
        runCatching { isReplaceable }.getOrDefault(false),
        data is Ageable,
        data is Waterlogged && data.isWaterlogged,
        expected,
    )
}

/** World moved on from [expected] by itself: fluid, fire, gravity, growth, weathering, a state flip. */
@Unstable
internal fun drifted(
    type: Material,
    liquid: Boolean,
    replaceable: Boolean,
    ageable: Boolean,
    waterlogged: Boolean,
    expected: BlockShape,
): Boolean {
    if (type.isAir || liquid || type.hasGravity() || type in NaturalBlock.materials) return true
    if (replaceable) return true
    val was = BlockDataCache.of(expected.data)?.material ?: return false
    if (type == was) return true
    if (ageable || waterlogged && was.isAir) return true
    if (was.poursLikeFluid() || was.isAir) return type in FluidMadeBlock.materials
    if (type in SoilBlock.materials && was in SoilBlock.materials) return true
    return Weathering.sameCore(type, was)
}
