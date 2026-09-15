package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.listener.support.FluidProvenance
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.packed
import com.tracel.plugin.util.unpackX
import com.tracel.plugin.util.unpackY
import com.tracel.plugin.util.unpackZ
import org.bukkit.Material
import org.bukkit.World

internal const val FLOW_REACH = 8
internal const val MAX_DRAINED = 4096

// TODO: rewrite this stupid shit

@Unstable
internal fun drainFlowing(
    world: World,
    steps: List<StructureStep.SetBlock>,
    chunks: Set<Long>,
): Boolean {
    val keep = HashSet<Long>(steps.size)
    val seeds = ArrayList<Long>()
    val roots = HashSet<BlockPos>()
    var minX = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var minZ = Int.MAX_VALUE
    var maxZ = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (step in steps) {
        keep += packed(step.at.x, step.at.y, step.at.z)
        if (!isFluidShape(step.target) && !isFluidShape(step.expected)) continue
        seeds += packed(step.at.x, step.at.y, step.at.z)
        roots += step.at
        if (step.at.x < minX) minX = step.at.x
        if (step.at.x > maxX) maxX = step.at.x
        if (step.at.z < minZ) minZ = step.at.z
        if (step.at.z > maxZ) maxZ = step.at.z
        if (step.at.y > maxY) maxY = step.at.y
    }
    if (seeds.isEmpty()) return true

    val fallbackMinX = minX - FLOW_REACH
    val fallbackMaxX = maxX + FLOW_REACH
    val fallbackMinZ = minZ - FLOW_REACH
    val fallbackMaxZ = maxZ + FLOW_REACH

    val worldId = WorldId(world.uid)
    val seen = HashSet<Long>(seeds.size * 4)
    val queue = ArrayDeque(seeds)
    var drained = 0
    while (queue.isNotEmpty() && drained < MAX_DRAINED) {
        val at = queue.removeFirst()
        for (face in CARDINAL) {
            val x = unpackX(at) + face.modX
            val y = unpackY(at) + face.modY
            val z = unpackZ(at) + face.modZ
            if (y > maxY) continue
            if (chunkKey(x, z) !in chunks) continue
            val pos = packed(x, y, z)
            if (!seen.add(pos) || pos in keep) continue
            val neighbor = world.getBlockAt(x, y, z)
            if (!neighbor.holdsFreeFluid()) continue
            val data = neighbor.blockData
            if (!isFlowingLiquid(data)) continue

            val blockPos = BlockPos(worldId, x, y, z)
            val root = FluidProvenance.rootOf(blockPos)
            val traced = root != null && root in roots
            if (!traced) {
                val inFallbackRadius = x in fallbackMinX..fallbackMaxX && z in fallbackMinZ..fallbackMaxZ
                if (!inFallbackRadius) continue
            }

            neighbor.setType(Material.AIR, false)
            drained++
            queue.add(pos)
        }
    }
    return queue.isEmpty()
}
