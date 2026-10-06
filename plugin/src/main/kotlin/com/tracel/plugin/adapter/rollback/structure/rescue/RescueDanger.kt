package com.tracel.plugin.adapter.rollback.structure.rescue

import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.specifics.block.HazardBlock
import java.util.*
import kotlin.math.floor
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox

/** Rescue danger. */
internal suspend fun StructureRestorer.endangered(
    world: World,
    box: BoundingBox,
    flying: Boolean,
    underfoot: Boolean,
): Boolean {
    for (cells in cellsByChunk(box)) {
        val (x, y, z) = cells.first()
        val bad = inChunk(world, x, y, z) {
            cells.any { (cx, cy, cz) ->
                val block = world.getBlockAt(cx, cy, cz)
                block.type in HazardBlock.materials || blocksBody(block, box)
            }
        }
        if (bad) return true
    }
    return !(flying || !underfoot) && !hasGround(world, box)
}

private suspend fun StructureRestorer.hasGround(world: World, box: BoundingBox): Boolean {
    val feet = floor(box.minY - 1e-4).toInt()
    val groups = HashMap<Long, MutableList<IntArray>>()
    for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
        for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt())
            groups.getOrPut(chunkOf(x, z)) { ArrayList() } += intArrayOf(x, z)
    for (columns in groups.values) {
        val (x, z) = columns.first()
        val grounded = inChunk(world, x, feet, z) {
            // A chunk nobody loaded is no evidence of a hole
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@inChunk true
            (0..FALL_TOLERANCE).any { dy ->
                columns.any { (cx, cz) ->
                    val block = world.getBlockAt(cx, feet - dy, cz)
                    block.type !in HazardBlock.materials && (block.isLiquid || !block.isPassable)
                }
            }
        }
        if (grounded) return true
    }
    return false
}

private fun blocksBody(block: Block, box: BoundingBox): Boolean {
    if (block.isPassable) return false
    val shape = block.collisionShape
    return shape.boundingBoxes.any {
        it.shift(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()).overlaps(box)
    }
}
