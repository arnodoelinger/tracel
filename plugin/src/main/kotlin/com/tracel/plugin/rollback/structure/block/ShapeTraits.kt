package com.tracel.plugin.rollback.structure.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.rollback.structure.fluid.snowShapeUncached
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

/**
 * What a block state means to a restore, worked out once per distinct state string.
 *
 * Each of these used to go through [BlockDataCache] and the server's material lookup every time it was asked, and a
 * restore asks several of them about every one of a few million steps while there are only thousands of states.
 */
@Unstable
internal object ShapeTraits {
    const val STANDS_ALONE = 1
    const val GRAVITY = 2
    const val SOLID = 4
    const val FIRE = 8
    const val AIR = 16
    const val SNOW = 32
    const val FLUID = 64

    private val cache = ConcurrentHashMap<String, Int>()

    /** The bitwise combination of traits for [shape], cached by its state string. */
    fun of(shape: BlockShape): Int = cache.getOrPut(shape.data.value) { compute(shape) }

    private fun compute(shape: BlockShape): Int {
        val material = BlockDataCache.of(shape.data)?.material
        var bits = 0
        if (material == null || (material.isSolid && !material.hasGravity())) bits = bits or STANDS_ALONE
        if (material?.hasGravity() == true) bits = bits or GRAVITY
        if (material?.isSolid == true) bits = bits or SOLID
        if (material == Material.FIRE || material == Material.SOUL_FIRE) bits = bits or FIRE
        if (material?.isAir == true) bits = bits or AIR
        if (snowShapeUncached(shape)) bits = bits or SNOW
        if (isFluidShape(shape)) bits = bits or FLUID
        return bits
    }
}
