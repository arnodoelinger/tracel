package com.tracel.plugin.rollback.structure.block

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.chunkKeyX
import com.tracel.plugin.util.chunkKeyZ
import org.bukkit.World
import org.bukkit.entity.FallingBlock

/** Falling block. */
@Unstable
internal fun overlappingFalling(world: World, blocks: List<StructureStep.SetBlock>): List<FallingBlock> {
    if (blocks.isEmpty()) return emptyList()
    // val spans = HashMap<Long, IntArray>(blocks.size)
    val spans = HashMap<Long, IntArray>(blocks.size)
    val chunks = LinkedHashSet<Long>(blocks.size)
    for ((at, target, expected) in blocks) {
        if (!target.hasGravity() && !expected.hasGravity()) continue
        val column = (at.x.toLong() shl 32) or (at.z.toLong() and 0xFFFFFFFFL)
        val span = spans.getOrPut(column) { intArrayOf(at.y, at.y) }
        if (at.y < span[0]) span[0] = at.y
        if (at.y > span[1]) span[1] = at.y
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
            val column = (loc.blockX.toLong() shl 32) or (loc.blockZ.toLong() and 0xFFFFFFFFL)
            val span = spans[column] ?: continue
            if (loc.blockY >= span[0] - 1 && loc.blockY <= span[1] + 1) out += entity // +
        }
    }
    return out
}
