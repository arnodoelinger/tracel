package com.tracel.plugin.adapter.rollback.structure.block.write

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.adapter.rollback.structure.block.check.acceptable
import com.tracel.plugin.adapter.rollback.structure.block.check.matchesExpected
import com.tracel.plugin.adapter.rollback.structure.block.check.onTarget
import com.tracel.plugin.adapter.rollback.structure.block.check.sameState
import com.tracel.plugin.adapter.rollback.structure.block.paint
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.block.write.Applied
import com.tracel.plugin.rollback.structure.block.write.Outcome
import com.tracel.plugin.rollback.structure.block.write.Refused
import com.tracel.plugin.rollback.structure.block.write.Unchanged
import com.tracel.plugin.specifics.block.isAirLike
import com.tracel.plugin.specifics.block.isMovingPiston
import com.tracel.plugin.specifics.block.ticksOnly
import org.bukkit.block.*
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack

/** Apply [step]; report with the live standing shape. */
@Unstable
internal fun StructureRestorer.apply(
    block: Block,
    step: StructureStep.SetBlock,
    force: Boolean,
    dumpHeldCargo: Boolean
): Outcome {
    if (step.target.isMovingPiston()) return Refused("was caught mid-push by a piston; nothing to put back")
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

    if (targetData != null) {
        block.paint(targetData)
    } else {
        step.target.applyTo(block, physics = false)
    }
    block.wakeBubbles()
    runCatching { block.settleLeaves() }

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
