package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.packed
import org.bukkit.World

// TODO: rewrite

@Unstable
internal fun settleFluids(world: World, steps: List<StructureStep.SetBlock>, chunks: Set<Long>) {
    if (steps.isEmpty()) return
    val ticked = HashSet<Long>(steps.size * 2)

    val emptied = HashSet<Long>(steps.size)
    for ((at, target) in steps) {
        if (target == BlockShape.AIR) emptied += packed(at.x, at.y, at.z)
    }

    for (step in steps) {
        if (packed(step.at.x, step.at.y, step.at.z) in emptied) continue
        tickFluidAt(world, step.at.x, step.at.y, step.at.z, chunks, ticked, emptied)
        for (face in CARDINAL) {
            tickFluidAt(
                world, step.at.x + face.modX, step.at.y + face.modY, step.at.z + face.modZ,
                chunks, ticked, emptied,
            )
        }
    }
}

@Unstable
internal fun tickFluidAt(
    world: World,
    x: Int,
    y: Int,
    z: Int,
    chunks: Set<Long>,
    ticked: MutableSet<Long>,
    emptied: Set<Long> = emptySet(),
) {
    if (chunkKey(x, z) !in chunks) return
    for (face in CARDINAL) {
        if (packed(x + face.modX, y + face.modY, z + face.modZ) in emptied) return
    }
    if (!ticked.add(packed(x, y, z))) return
    val block = world.getBlockAt(x, y, z)
    if (!block.holdsFreeFluid()) return
    runCatching { block.fluidTick() }
}
