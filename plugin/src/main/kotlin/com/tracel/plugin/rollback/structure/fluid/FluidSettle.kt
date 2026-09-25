package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.rollback.structure.block.isAir
import com.tracel.plugin.util.packed
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Waterlogged

// TODO: rewrite

@Unstable
internal fun settleFluids(world: World, steps: List<StructureStep.SetBlock>, owns: (Int, Int) -> Boolean) {
    if (steps.isEmpty()) return
    val ticked = HashSet<Long>(steps.size * 2)

    val emptied = HashSet<Long>(steps.size)
    for ((at, target) in steps) {
        if (target.isAir()) emptied += packed(at.x, at.y, at.z)
    }

    for ((at, target, expected) in steps) {
        if (target.feedsBubbles() || expected.feedsBubbles()) {
            if (owns(at.x, at.z)) runCatching { world.getBlockAt(at.x, at.y, at.z).tick() }
        }
        if (!target.touchesFluid() && !expected.touchesFluid()) continue
        if (packed(at.x, at.y, at.z) in emptied) continue
        tickFluidAt(world, at.x, at.y, at.z, owns, ticked, emptied)
        for (face in CARDINAL) {
            tickFluidAt(world, at.x + face.modX, at.y + face.modY, at.z + face.modZ, owns, ticked, emptied)
        }
    }
}

internal fun tickFluidAt(
    world: World,
    x: Int,
    y: Int,
    z: Int,
    owns: (Int, Int) -> Boolean,
    ticked: MutableSet<Long>,
    emptied: Set<Long> = emptySet(),
) {
    if (!owns(x, z)) return
    for (face in CARDINAL) {
        if (packed(x + face.modX, y + face.modY, z + face.modZ) in emptied) return
    }
    if (!ticked.add(packed(x, y, z))) return
    val block = world.getBlockAt(x, y, z)
    if (!block.holdsFreeFluid()) return
    runCatching { block.fluidTick() }
}

private fun BlockShape.touchesFluid(): Boolean {
    if (isFluidShape(this)) return true
    val data = BlockDataCache.of(data) ?: return true
    if (data is Waterlogged) return true
    return !data.material.isSolid
}

private fun BlockShape.feedsBubbles(): Boolean {
    val material = BlockDataCache.of(data)?.material ?: return false
    return material == Material.SOUL_SAND || material == Material.MAGMA_BLOCK
}
