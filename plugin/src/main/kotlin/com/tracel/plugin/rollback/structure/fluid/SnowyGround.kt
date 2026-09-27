package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.rollback.structure.block.paint
import com.tracel.plugin.util.chunkKey
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Snowable

// TODO: rewrite

@Unstable
internal fun isSnowMaterial(material: Material): Boolean =
    material == Material.SNOW || material == Material.SNOW_BLOCK || material == Material.POWDER_SNOW

@Unstable
internal fun isSnowShape(shape: BlockShape): Boolean {
    val data = BlockDataCache.of(shape.data)
    if (data != null) return isSnowMaterial(data.material)
    val material = shape.data.value.substringBefore('[').substringAfter(':')
    return material == "snow" || material == "snow_block" || material == "powder_snow"
}

@Unstable
internal fun fixSnowyGround(world: World, steps: List<StructureStep.SetBlock>, chunks: Set<Long>) {
    for ((at, target, expected) in steps) {
        if (!isSnowShape(target) && !isSnowShape(expected)) continue
        val x = at.x
        val z = at.z
        if (chunkKey(x, z) !in chunks) continue
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
