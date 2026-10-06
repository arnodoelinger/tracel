package com.tracel.plugin.adapter.rollback.structure.group

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.applyTo
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.rollback.structure.block.PalettePaste
import com.tracel.plugin.adapter.rollback.structure.block.airBlockData
import com.tracel.plugin.adapter.rollback.structure.block.blockAt
import com.tracel.plugin.adapter.rollback.structure.block.check.isFire
import com.tracel.plugin.adapter.rollback.structure.block.paint
import com.tracel.plugin.specifics.block.AIR
import com.tracel.plugin.specifics.block.isFireBlock
import org.bukkit.World

/** Fire left on or above a restored block. Reads the section directly and only opens a Bukkit block when it is fire. */
internal fun extinguish(
    paste: PalettePaste,
    world: World,
    at: BlockPos,
    target: BlockShape,
    planned: Lazy<Map<BlockPos, StructureStep.SetBlock>>,
    unwritten: Set<BlockPos>,
    applied: MutableList<StructureStep>,
) {
    if (!target.isFire() && paste.fireAt(world, at.x, at.y, at.z)) {
        val block = world.blockAt(at)
        val data = BlockDataCache.of(target.data)
        if (data != null && target.extras == null) block.paint(data) else target.applyTo(block, physics = false)
    }
    if (target.isFire()) return
    if (!paste.fireAt(world, at.x, at.y + 1, at.z)) return
    val above = at.copy(y = at.y + 1)
    if (planned.value[above]?.target?.isFire() == true || above in unwritten) return
    val block = world.getBlockAt(above.x, above.y, above.z)
    val burning = block.toShape()
    block.paint(airBlockData())
    applied += StructureStep.SetBlock(above, AIR, burning)
}

private fun PalettePaste.fireAt(world: World, x: Int, y: Int, z: Int): Boolean {
    val state = read(x, y, z) ?: return world.getBlockAt(x, y, z).isFire()
    return material(state)?.isFireBlock() == true
}
