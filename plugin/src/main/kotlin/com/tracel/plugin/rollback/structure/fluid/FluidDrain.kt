package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.rollback.structure.block.ShapeTraits
import com.tracel.plugin.rollback.structure.block.PalettePaste
import com.tracel.plugin.rollback.structure.block.airBlockData
import com.tracel.plugin.rollback.structure.block.paint
import com.tracel.plugin.util.LongHashSet
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

private val NO_FLOW = Flow(Material.AIR, 0)

// TODO: rewrite this stupid shit

private fun isFluid(shape: BlockShape): Boolean = ShapeTraits.of(shape) and ShapeTraits.FLUID != 0

@Unstable
internal fun drainFlowing(world: World, steps: List<StructureStep.SetBlock>, owns: (Int, Int) -> Boolean): Boolean {
    val seeds = ArrayList<Long>()
    for (step in steps) {
        if (isFluid(step.expected) && !isFluid(step.target)) seeds += packed(step.at.x, step.at.y, step.at.z)
    }
    if (seeds.isEmpty()) return true
    val written = LongHashSet(steps.size)
    for (step in steps) written += packed(step.at.x, step.at.y, step.at.z)

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

    val flows = HashMap<Long, Flow>(reach.size * 2)
    fun look(pos: Long): Flow? = flows.getOrPut(pos) { world.flowAt(pos) }
    for (pos in reach.keys) {
        look(pos)
        look(packed(unpackX(pos), unpackY(pos) + 1, unpackZ(pos)))
        for (face in HORIZONTAL) look(packed(unpackX(pos) + face.modX, unpackY(pos), unpackZ(pos) + face.modZ))
    }
    val drained = drainOrder(reach.keys, flows)

    var left = MAX_DRAINED
    val air = airBlockData()
    val paste = PalettePaste.tryOpen(world)
    paste?.bind()
    try {
        for (pos in drained) {
            if (left-- <= 0) return false
            world.getBlockAt(unpackX(pos), unpackY(pos), unpackZ(pos)).paint(air)
        }
    } finally {
        paste?.close()
    }
    return complete
}

internal class Flow(val kind: Material, val level: Int)

/** Cells that nothing still feeds, sources first only once their downstream is gone. One pass, no repeated world reads. */
internal fun drainOrder(cells: Collection<Long>, flows: Map<Long, Flow>): List<Long> {
    val pending = cells.toHashSet()
    val drained = HashSet<Long>(pending.size)
    val ready = ArrayDeque<Long>()
    for (pos in pending) if (!fedBy(pos, drained, flows)) ready += pos
    val order = ArrayList<Long>(pending.size)
    while (ready.isNotEmpty()) {
        val pos = ready.removeFirst()
        if (!pending.remove(pos)) continue
        drained += pos
        order += pos
        val x = unpackX(pos)
        val y = unpackY(pos)
        val z = unpackZ(pos)
        consider(packed(x, y - 1, z), pending, drained, flows, ready)
        for (face in HORIZONTAL) consider(packed(x + face.modX, y, z + face.modZ), pending, drained, flows, ready)
    }
    return order
}

private fun consider(
    pos: Long,
    pending: Set<Long>,
    drained: Set<Long>,
    flows: Map<Long, Flow>,
    ready: ArrayDeque<Long>,
) {
    if (pos !in pending || fedBy(pos, drained, flows)) return
    ready += pos
}

private fun fedBy(pos: Long, drained: Set<Long>, flows: Map<Long, Flow>): Boolean {
    val here = flows[pos] ?: return true
    if (here.kind == Material.AIR) return true
    val x = unpackX(pos)
    val y = unpackY(pos)
    val z = unpackZ(pos)
    val aboveKey = packed(x, y + 1, z)
    val above = flows[aboveKey]
    if (aboveKey !in drained && above != null && above.kind == here.kind) return true
    if (here.level >= FALLING) return false
    for (face in HORIZONTAL) {
        val sideKey = packed(x + face.modX, y, z + face.modZ)
        if (sideKey in drained) continue
        val side = flows[sideKey] ?: continue
        if (side.kind != here.kind) continue
        if (side.level == 0 || side.level >= FALLING || side.level < here.level) return true
    }
    return false
}

private fun World.flowAt(pos: Long): Flow {
    val block = getBlockAt(unpackX(pos), unpackY(pos), unpackZ(pos))
    val kind = fluidOf(block) ?: return NO_FLOW
    return Flow(kind, levelOf(block))
}