package com.tracel.plugin.adapter.rollback.structure.block.write

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.rollback.structure.block.paint
import com.tracel.plugin.specifics.block.LEAF_MAX_DISTANCE
import com.tracel.plugin.specifics.block.isLog
import org.bukkit.block.*
import org.bukkit.block.data.type.Leaves

private val LEAF_FACES =
    arrayOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

private const val MAX_LEAF_SETTLE = 4_096

@Unstable
internal fun Block.settleLeaves() {
    val start = distanceOf(this) ?: return
    val queue = ArrayDeque<Pair<Block, Int>>()
    queue += this to start
    var budget = MAX_LEAF_SETTLE
    while (queue.isNotEmpty() && budget-- > 0) {
        val (cell, distance) = queue.removeFirst()
        for (face in LEAF_FACES) {
            val next = cell.getRelative(face)
            val leaves = next.blockData as? Leaves ?: continue
            if (leaves.isPersistent || distance + 1 >= leaves.distance) continue
            leaves.distance = distance + 1
            next.paint(leaves)
            if (distance + 1 < LEAF_MAX_DISTANCE) queue += next to distance + 1
        }
    }
}

private fun distanceOf(block: Block): Int? {
    if (block.type.isLog()) return 0
    val leaves = block.blockData as? Leaves ?: return null
    if (leaves.isPersistent) return null
    var best = leaves.distance
    for (face in LEAF_FACES) {
        val near = block.getRelative(face)
        val d = if (near.type.isLog()) 0 else (near.blockData as? Leaves)?.distance ?: continue
        if (d + 1 < best) best = d + 1
    }
    if (best < leaves.distance) {
        leaves.distance = best
        block.paint(leaves)
    }
    return best.takeIf { it < LEAF_MAX_DISTANCE }
}
