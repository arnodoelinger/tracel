package com.tracel.plugin.util.geometry

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.world.WorldId
import com.tracel.plugin.command.args.scope.LookupScope

/**
 * The [LookupRegion] a [scope] covers around the block at [blockX], [blockY], [blockZ] of [world].
 *
 * @return `null` without a scope
 */
fun lookupRegion(
    world: WorldId,
    blockX: Int,
    blockY: Int,
    blockZ: Int,
    scope: LookupScope?,
    horizontalOnly: Boolean = false,
): LookupRegion? {
    if (scope == null) return null

    val chunkX = blockX shr 4
    val chunkZ = blockZ shr 4

    val (chunkRadius, blockRadius) = when (scope) {
        is LookupScope.Blocks -> Pair((scope.radius shr 4) + 1, scope.radius)
        is LookupScope.Chunks -> Pair(scope.radius, null)
        LookupScope.CurrentChunk -> Pair(0, null)
        LookupScope.CurrentBlock -> Pair(0, 0)
    }

    val (minY, maxY) = if (scope == LookupScope.CurrentBlock && !horizontalOnly) {
        Pair(blockY - 1, blockY)
    } else if (blockRadius != null && !horizontalOnly) {
        Pair(blockY - blockRadius, blockY + blockRadius)
    } else {
        Pair(Int.MIN_VALUE, Int.MAX_VALUE)
    }

    return LookupRegion(
        world = world,
        minTileX = blockRadius?.let { (blockX - it) shr 4 } ?: (chunkX - chunkRadius),
        maxTileX = blockRadius?.let { (blockX + it) shr 4 } ?: (chunkX + chunkRadius),
        minTileZ = blockRadius?.let { (blockZ - it) shr 4 } ?: (chunkZ - chunkRadius),
        maxTileZ = blockRadius?.let { (blockZ + it) shr 4 } ?: (chunkZ + chunkRadius),
        minX = blockRadius?.let { blockX - it } ?: Int.MIN_VALUE,
        maxX = blockRadius?.let { blockX + it } ?: Int.MAX_VALUE,
        minY = minY,
        maxY = maxY,
        minZ = blockRadius?.let { blockZ - it } ?: Int.MIN_VALUE,
        maxZ = blockRadius?.let { blockZ + it } ?: Int.MAX_VALUE
    )
}
