package com.tracel.plugin.rollback.structure.block

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.applyTo
import com.tracel.plugin.adapter.block.cargoSlots
import com.tracel.plugin.adapter.block.holdsAnything
import com.tracel.plugin.adapter.block.mayHaveTile
import com.tracel.plugin.adapter.block.resyncCargo
import com.tracel.plugin.adapter.block.snapshot
import com.tracel.plugin.adapter.block.takeAll
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.rollback.structure.StructureRestorer
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack

/** Apply [step]; report with the live standing shape. */
internal fun StructureRestorer.apply(block: Block, step: StructureStep.SetBlock, force: Boolean, dumpHeldCargo: Boolean): Outcome {
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
internal fun StructureRestorer.applyPlain(block: Block, step: StructureStep.SetBlock, force: Boolean): Outcome {
    val targetData = BlockDataCache.of(step.target.data)

    val type = block.type
    val alreadyTarget = targetData != null && when {
        targetData.material.isAir -> type.isAir
        type != targetData.material -> false
        else -> block.blockData.onTarget(targetData)
    }
    val matchesExpected = matchesExpected(block, step.expected)

    if (alreadyTarget && matchesExpected) return Applied(step)

    if (!matchesExpected && !force) {
        if (step.expected != BlockShape.AIR || !runCatching { block.isReplaceable }.getOrDefault(false)) {
            return Refused("is ${block.blockData.asString}, expected ${step.expected.data.value}")
        }
    }

    // Capture standing before setBlockData overwrites it
    val standing = if (matchesExpected) null else block.blockData.asString

    if (!alreadyTarget && targetData != null) {
        block.setBlockData(targetData, false)
    } else if (!alreadyTarget) {
        step.target.applyTo(block, physics = false)
    }

    if (standing == null) return Applied(step)
    return Applied(step.copy(expected = BlockShape(BlockDataKey(standing))), differed = true)
}

/** Tile chest / sign / banner, etc. */
internal fun StructureRestorer.applyTile(block: Block, step: StructureStep.SetBlock, force: Boolean, dumpHeldCargo: Boolean): Outcome {
    val standing = block.toShape()
    if (standing == step.target) return Applied(step.copy(expected = standing))

    val differed = !acceptable(block, standing, step.expected)
    if (differed && !force) {
        return Refused("is ${standing.data.value}, expected ${step.expected.data.value}")
    }

    val held = block.cargoSlots()?.takeIf { it.holdsAnything() }
    if (held != null) {
        if (step.target == BlockShape.AIR) {
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

    if (step.target == BlockShape.AIR) {
        services.selfManagedSpawns.whileSpawning {
            step.target.applyTo(block, physics = false)
        }
    } else {
        step.target.applyTo(block, physics = false)
        if (step.expected == BlockShape.AIR) {
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
