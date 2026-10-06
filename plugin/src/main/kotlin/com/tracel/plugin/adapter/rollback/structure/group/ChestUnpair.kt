package com.tracel.plugin.adapter.rollback.structure.group

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.rollback.structure.block.paint
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest

/** Unpair orphaned chests. */
internal fun unpairOrphanedChests(world: World, applied: List<StructureStep>) {
    for (step in applied) {
        if (step !is StructureStep.SetBlock) continue
        val was = BlockDataCache.of(step.expected.data) as? Chest ?: continue
        if (was.type == Chest.Type.SINGLE || BlockDataCache.of(step.target.data) is Chest) continue
        val towards = if (was.type == Chest.Type.LEFT) was.facing.clockwise() else was.facing.counterClockwise()
        val partner = world.getBlockAt(step.at.x + towards.modX, step.at.y, step.at.z + towards.modZ)
        val data = partner.blockData as? Chest ?: continue
        if (data.type == Chest.Type.SINGLE || data.facing != was.facing) continue
        data.type = Chest.Type.SINGLE
        partner.paint(data)
    }
}

private fun BlockFace.clockwise(): BlockFace = when (this) {
    BlockFace.NORTH -> BlockFace.EAST
    BlockFace.EAST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.WEST
    else -> BlockFace.NORTH
}

private fun BlockFace.counterClockwise(): BlockFace = when (this) {
    BlockFace.NORTH -> BlockFace.WEST
    BlockFace.WEST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.EAST
    else -> BlockFace.NORTH
}
