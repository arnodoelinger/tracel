package com.tracel.plugin.rollback.structure.block

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.rollback.structure.StructureRestorer
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.*
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.type.BubbleColumn
import org.bukkit.block.data.type.Leaves
import org.bukkit.inventory.ItemStack

private val LEAF_FACES =
    arrayOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

private const val LEAF_MAX_DISTANCE = 7
private const val MAX_LEAF_SETTLE = 4_096
private const val MAX_BUBBLE_COLUMN = 384

/** Apply [step]; report with the live standing shape. */
@Unstable
internal fun StructureRestorer.apply(
    block: Block,
    step: StructureStep.SetBlock,
    force: Boolean,
    dumpHeldCargo: Boolean
): Outcome {
    if (step.target.data.value.startsWith("minecraft:moving_piston")) return Refused("was caught mid-push by a piston; nothing to put back")
    if (block.mayHaveTile() || step.target.extras != null || step.expected.extras != null) {
        return applyTile(block, step, force, dumpHeldCargo)
    }
    return applyPlain(block, step, force)
}

/**
 * Plain block.
 *
 * Compare parsed [BlockData], write once.
 */
@Unstable
internal fun StructureRestorer.applyPlain(block: Block, step: StructureStep.SetBlock, force: Boolean): Outcome {
    val targetData = BlockDataCache.of(step.target.data)

    val type = block.type
    val alreadyTarget = targetData != null && when {
        targetData.material.isAir -> type.isAir
        type != targetData.material -> false
        else -> block.blockData.onTarget(targetData)
    }
    if (alreadyTarget) return Unchanged
    val matchesExpected = matchesExpected(block, step.expected)

    if (!matchesExpected && !force) {
        if (!step.expected.isAirLike || !runCatching { block.isReplaceable }.getOrDefault(false)) {
            return Refused("is ${block.blockData.asString}, expected ${step.expected.data.value}")
        }
    }

    val live = block.blockData
    val exact =
        BlockDataCache.of(step.expected.data)?.let { live.sameState(it) } ?: (step.expected.isAirLike && block.isEmpty)
    val standing = if (exact) null else live.asString

    if (!alreadyTarget && targetData != null) {
        block.paint(targetData)
    } else if (!alreadyTarget) {
        step.target.applyTo(block, physics = false)
    }
    if (!alreadyTarget) {
        block.wakeBubbles()
        runCatching { block.settleLeaves() }
    }

    if (standing == null) return Applied(step)
    return Applied(step.copy(expected = BlockShape(BlockDataKey(standing))), differed = !matchesExpected)
}

/** Tile chest / sign / banner, etc. */
internal fun StructureRestorer.applyTile(
    block: Block,
    step: StructureStep.SetBlock,
    force: Boolean,
    dumpHeldCargo: Boolean
): Outcome {
    if (block.blockData.asString == step.target.data.value && block.ticksOnly()) return Unchanged
    val standing = block.toShape()
    if (standing == step.target) return Unchanged

    val differed = !acceptable(block, standing, step.expected)
    if (differed && !force) {
        return Refused("is ${standing.data.value}, expected ${step.expected.data.value}")
    }

    val held = block.cargoSlots()?.takeIf { it.holdsAnything() }
    if (held != null) {
        if (step.target.isAirLike) {
            if (!dumpHeldCargo) return Refused("still holds material nobody withdrew")
            held.takeAll()
            services.selfManagedSpawns.whileSpawning {
                step.target.applyTo(block, physics = false)
            }
            return Applied(step.copy(expected = standing), differed)
        }
        val targetMaterial = BlockDataCache.of(step.target.data)?.material
        if (targetMaterial != block.type) {
            return Refused("a ${block.type.name.lowercase()} stands here still holding material nobody withdrew")
        }
        val carried = held.snapshot()
        step.target.applyTo(block, physics = false)
        carryOver(block, carried)
        return Applied(step.copy(expected = standing), differed)
    }

    if (step.target.isAirLike) {
        services.selfManagedSpawns.whileSpawning {
            step.target.applyTo(block, physics = false)
        }
    } else {
        step.target.applyTo(block, physics = false)
        if (step.expected.isAirLike) {
            block.cargoSlots()?.takeAll()
            block.resyncCargo()
        }
    }
    return Applied(step.copy(expected = standing), differed)
}

/** Re-seat [saved] after the tile rewrite. */
internal fun StructureRestorer.carryOver(block: Block, saved: List<ItemStack?>) {
    val slots = block.cargoSlots() ?: return
    for ((slot, stack) in saved.withIndex()) {
        if (slot >= slots.size) break
        if (stack != null && !stack.isEmpty) slots.set(slot, stack)
    }
    block.resyncCargo()
}

@Unstable
internal fun Block.wakeBubbles() {
    val drag = when (type) {
        Material.SOUL_SAND -> false
        Material.MAGMA_BLOCK -> true
        else -> null
    }
    var cell = getRelative(BlockFace.UP)
    var left = MAX_BUBBLE_COLUMN
    while (left-- > 0) {
        val data = cell.blockData
        val source = (data as? Levelled)?.let { cell.type == Material.WATER && it.level == 0 } ?: false
        when {
            drag != null && (source || cell.type == Material.BUBBLE_COLUMN) ->
                cell.paint(
                    (Material.BUBBLE_COLUMN.createBlockData() as BubbleColumn).also { it.isDrag = drag },
                )

            drag == null && cell.type == Material.BUBBLE_COLUMN -> cell.paint(Material.WATER.createBlockData())

            else -> return
        }
        cell = cell.getRelative(BlockFace.UP)
    }
}

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
    if (Tag.LOGS.isTagged(block.type)) return 0
    val leaves = block.blockData as? Leaves ?: return null
    if (leaves.isPersistent) return null
    var best = leaves.distance
    for (face in LEAF_FACES) {
        val near = block.getRelative(face)
        val d = if (Tag.LOGS.isTagged(near.type)) 0 else (near.blockData as? Leaves)?.distance ?: continue
        if (d + 1 < best) best = d + 1
    }
    if (best < leaves.distance) {
        leaves.distance = best
        block.paint(leaves)
    }
    return best.takeIf { it < LEAF_MAX_DISTANCE }
}

private fun Block.ticksOnly(): Boolean = when (runCatching { getState(false) }.getOrNull()) {
    is Furnace, is BrewingStand, is Campfire -> true
    else -> false
}
