package com.tracel.plugin.adapter.rollback.structure.block.check

import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.BlockLikeness
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.specifics.block.AIR
import com.tracel.plugin.specifics.block.isAirLike
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/** Is [block] already what [expected] wants. */
internal fun StructureRestorer.matchesExpected(block: Block, expected: BlockShape): Boolean {
    val data = BlockDataCache.of(expected.data) ?: return expected == AIR && block.isEmpty
    if (data.material.isAir) return block.isEmpty
    val current = block.blockData
    return current.sameState(data) || BlockLikeness.sameEnough(current.asString, expected.data.value)
}

/**
 * Exact match: same material and same state, either by property comparison
 * or by string form.
 */
internal fun BlockData.sameState(expected: BlockData): Boolean {
    if (expected.material.isAir) return material.isAir
    if (material != expected.material) return false
    return matches(expected) || asString == expected.asString
}

/** Whether it already is [target]. */
internal fun BlockData.onTarget(target: BlockData): Boolean = sameState(target)

/** Whether leaving [standing] alone is fine, even if it isn't a byte-exact [expected]. */
internal fun StructureRestorer.acceptable(block: Block, standing: BlockShape, expected: BlockShape): Boolean {
    if (standing == expected) return true
    if (BlockLikeness.sameEnough(standing.data.value, expected.data.value)) return true
    if (!expected.isAirLike) return false
    return runCatching { block.isReplaceable }.getOrDefault(false)
}
