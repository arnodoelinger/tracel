package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.util.packed
import com.tracel.plugin.util.unpackX
import com.tracel.plugin.util.unpackY
import com.tracel.plugin.util.unpackZ
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace

internal const val FLOW_REACH = 8
internal const val MAX_DRAINED = 4096

private const val FALLING = 8

// TODO: rewrite this stupid shit

@Unstable
internal fun drainFlowing(world: World, steps: List<StructureStep.SetBlock>, owns: (Int, Int) -> Boolean): Boolean {
    val written = HashSet<Long>(steps.size * 2)
    val seeds = ArrayList<Long>()
    for (step in steps) {
        val at = packed(step.at.x, step.at.y, step.at.z)
        written += at
        if (isFluidShape(step.expected) && !isFluidShape(step.target)) seeds += at
    }
    if (seeds.isEmpty()) return true

    val reach = HashMap<Long, Int>()
    val queue = ArrayDeque<Long>()
    for (seed in seeds) {
        reach[seed] = 0
        queue += seed
    }
    var complete = true
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        val distance = reach.getValue(at)
        for (face in CARDINAL) {
            if (face == BlockFace.UP) continue
            val x = unpackX(at) + face.modX
            val y = unpackY(at) + face.modY
            val z = unpackZ(at) + face.modZ
            val pos = packed(x, y, z)
            if (pos in reach || pos in written) continue
            val next = if (face == BlockFace.DOWN) 0 else distance + 1
            if (next > FLOW_REACH || !owns(x, z)) continue
            if (!isFlowingLiquid(world.getBlockAt(x, y, z).blockData)) continue
            if (reach.size >= MAX_DRAINED * 2) {
                complete = false
                continue
            }
            reach[pos] = next
            queue += pos
        }
    }
    for (seed in seeds) reach.remove(seed)
    if (reach.isEmpty()) return complete

    val drained = HashSet<Long>()
    var changed = true
    while (changed) {
        changed = false
        for (pos in reach.keys) {
            if (pos in drained || fed(world, pos, drained)) continue
            drained += pos
            changed = true
        }
    }

    var left = MAX_DRAINED
    for (pos in drained) {
        if (left-- <= 0) return false
        world.getBlockAt(unpackX(pos), unpackY(pos), unpackZ(pos)).setType(Material.AIR, false)
    }
    return complete
}

private fun fed(world: World, pos: Long, drained: Set<Long>): Boolean {
    val x = unpackX(pos)
    val y = unpackY(pos)
    val z = unpackZ(pos)
    val block = world.getBlockAt(x, y, z)
    val fluid = fluidOf(block) ?: return true
    val level = levelOf(block)

    val above = block.getRelative(BlockFace.UP)
    if (packed(x, y + 1, z) !in drained && fluidOf(above) == fluid) return true
    if (level >= FALLING) return false

    for (face in HORIZONTAL) {
        val side = block.getRelative(face)
        if (packed(side.x, side.y, side.z) in drained || fluidOf(side) != fluid) continue
        val feeds = levelOf(side)
        if (feeds == 0 || feeds >= FALLING || feeds < level) return true
    }
    return false
}
