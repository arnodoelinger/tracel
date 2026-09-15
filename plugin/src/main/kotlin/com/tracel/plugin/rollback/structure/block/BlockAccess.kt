package com.tracel.plugin.rollback.structure.block

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.chunkKeyX
import com.tracel.plugin.util.chunkKeyZ
import org.bukkit.World
import org.bukkit.block.Block

/** Block position. */
internal fun World.blockAt(at: BlockPos): Block = getBlockAt(at.x, at.y, at.z)

/** Load chunks. */
internal fun StructureRestorer.loadChunks(world: World, chunks: Set<Long>) {
    for (key in chunks) world.getChunkAt(chunkKeyX(key), chunkKeyZ(key))
}

/** Chunks this region thread may touch. */
internal fun chunksOf(steps: List<StructureStep>): Set<Long> {
    val out = HashSet<Long>()
    for (step in steps) out += chunkKey(step.at.x, step.at.z)
    return out
}
