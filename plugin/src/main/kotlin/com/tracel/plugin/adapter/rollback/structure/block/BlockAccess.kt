package com.tracel.plugin.adapter.rollback.structure.block

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.util.geometry.chunkKey
import com.tracel.plugin.util.geometry.chunkKeyX
import com.tracel.plugin.util.geometry.chunkKeyZ
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

private val airBlock by lazy { Material.AIR.createBlockData() }

/** Block position. */
internal fun World.blockAt(at: BlockPos): Block = getBlockAt(at.x, at.y, at.z)

/** Cached air state. Created on first use, on the server. */
internal fun airBlockData(): BlockData = airBlock

/**
 * Write [data] with physics off.
 *
 * A bound [PalettePaste] puts plain states straight into the section. Tiles and a paste that
 * cannot see the chunk still go through `Bukkit`.
 */
@Unstable
internal fun Block.paint(data: BlockData) {
    val paste = PalettePaste.current()
    if (paste != null && paste.replace(x, y, z, data)) return
    setBlockData(data, false)
}

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
