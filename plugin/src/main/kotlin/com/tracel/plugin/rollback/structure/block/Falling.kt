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
    val floors = HashMap<Long, Int>(blocks.size)
    val chunks = LinkedHashSet<Long>(blocks.size)
    for (step in blocks) {
        val column = (step.at.x.toLong() shl 32) or (step.at.z.toLong() and 0xFFFFFFFFL)
        val floor = floors[column]
        if (floor == null || step.at.y < floor) floors[column] = step.at.y
        chunks += chunkKey(step.at.x, step.at.z)
    }
    val out = ArrayList<FallingBlock>()
    for (key in chunks) {
        for (entity in world.getChunkAt(chunkKeyX(key), chunkKeyZ(key)).entities) {
            if (entity !is FallingBlock) continue
            val loc = entity.location
            val column = (loc.blockX.toLong() shl 32) or (loc.blockZ.toLong() and 0xFFFFFFFFL)
            val floor = floors[column] ?: continue
            if (loc.blockY >= floor - 1) out += entity
        }
    }
    return out
}
