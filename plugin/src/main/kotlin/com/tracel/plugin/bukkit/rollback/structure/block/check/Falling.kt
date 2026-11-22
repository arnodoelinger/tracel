package com.tracel.plugin.bukkit.rollback.structure.block.check

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.packPair
import com.tracel.plugin.util.geometry.chunkKey
import com.tracel.plugin.util.geometry.chunkKeyX
import com.tracel.plugin.util.geometry.chunkKeyZ
import org.bukkit.World
import org.bukkit.entity.FallingBlock

private const val RETURNS = 2

/**
 * Falling blocks the steps would collide with, and the ones still in flight below a block
 * the steps put back: the log keeps no flight, so the column is how they are found.
 */
@Unstable
internal fun overlappingFalling(world: World, blocks: List<StructureStep.SetBlock>): List<FallingBlock> {
    if (blocks.isEmpty()) return emptyList()
    // val spans = HashMap<Long, IntArray>(blocks.size)
    val spans = HashMap<Long, IntArray>(blocks.size)
    val chunks = LinkedHashSet<Long>(blocks.size)
    for ((at, target, expected) in blocks) {
        if (!target.hasGravity() && !expected.hasGravity()) continue
        val column = packPair(at.x, at.z)
        val span = spans.getOrPut(column) { intArrayOf(at.y, at.y, Int.MIN_VALUE) }
        if (at.y < span[0]) span[0] = at.y
        if (at.y > span[1]) span[1] = at.y
        if (target.hasGravity() && !expected.hasGravity() && at.y > span[RETURNS]) span[RETURNS] = at.y
        chunks += chunkKey(at.x, at.z)
    }
    val out = ArrayList<FallingBlock>()
//    for (key in chunks) {
//        for (entity in world.getChunkAt(chunkKeyX(key), chunkKeyZ(key)).entities) {
//            if (entity !is FallingBlock) continue
//            val loc = entity.location
//            val column = (loc.blockX.toLong() shl 32) or (loc.blockZ.toLong() and 0xFFFFFFFFL)
//            val floor = floors[column] ?: continue
//            if (loc.blockY >= floor - 1) out += entity
//        }
//    }
    for (key in chunks) {
        for (entity in world.getChunkAt(chunkKeyX(key), chunkKeyZ(key)).entities) {
            if (entity !is FallingBlock) continue
            val loc = entity.location
            val column = packPair(loc.blockX, loc.blockZ)
            val span = spans[column] ?: continue
            val overlaps = loc.blockY >= span[0] - 1 && loc.blockY <= span[1] + 1
            if (overlaps || loc.blockY < span[RETURNS]) out += entity
        }
    }
    return out
}
