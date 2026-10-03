package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.rollback.structure.block.ShapeTraits
import com.tracel.plugin.rollback.structure.block.paint
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Snowable

// TODO: rewrite

/** Whether [material] is a snow material. */
@Unstable
internal fun isSnowMaterial(material: Material): Boolean =
    material == Material.SNOW || material == Material.SNOW_BLOCK || material == Material.POWDER_SNOW

/** Whether [shape] is a snow shape, using the cache. */
@Unstable
internal fun isSnowShape(shape: BlockShape): Boolean = ShapeTraits.of(shape) and ShapeTraits.SNOW != 0

/** Whether [shape] is a snow shape, without using the cache. */
internal fun snowShapeUncached(shape: BlockShape): Boolean {
    val data = BlockDataCache.of(shape.data)
    if (data != null) return isSnowMaterial(data.material)
    val material = shape.data.value.substringBefore('[').substringAfter(':')
    return material == "snow" || material == "snow_block" || material == "powder_snow"
}

/** Fix the snowy ground property of blocks after a rollback. */
@Unstable
internal suspend fun fixSnowyGround(
    world: World,
    steps: List<StructureStep.SetBlock>,
    owns: (Int, Int) -> Boolean,
    pace: suspend () -> Unit = {},
) {
    for ((at, target, expected) in steps) {
        pace()
        if (!isSnowShape(target) && !isSnowShape(expected)) continue
        val x = at.x
        val z = at.z
        if (!owns(x, z)) continue
        val below = world.getBlockAt(x, at.y - 1, z)
        val data = below.blockData
        if (data !is Snowable) continue
        val shouldBeSnowy = isSnowMaterial(world.getBlockAt(x, at.y, z).type)
        if (data.isSnowy != shouldBeSnowy) {
            data.isSnowy = shouldBeSnowy
            below.paint(data)
        }
    }
}
