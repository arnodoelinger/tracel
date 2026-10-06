package com.tracel.plugin.listener.world.cell

import com.tracel.plugin.adapter.command.vector
import com.tracel.plugin.util.command.ParsedBlockPos
import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.util.BoundingBox

/** Vanilla refuses `/fill` and `/clone` past this; capturing more would log a no-op. */
@Suppress("DEPRECATION")
internal fun World.blockModificationLimit(): Int =
    getGameRuleValue(GameRules.MAX_BLOCK_MODIFICATIONS) // TODO: elvis?

/** Inclusive block AABB; max is exclusive, same as [BoundingBox.of] for two blocks. */
internal fun blockBox(from: ParsedBlockPos, to: ParsedBlockPos): BoundingBox =
    BoundingBox.of(from.vector(), to.vector()).expandDirectional(1.0, 1.0, 1.0)

/** `x` blocks. */
internal fun BoundingBox.xBlocks(): IntRange = minX.toInt() until maxX.toInt()

/** `y` blocks. */
internal fun BoundingBox.yBlocks(): IntRange = minY.toInt() until maxY.toInt()

/** `z` blocks. */
internal fun BoundingBox.zBlocks(): IntRange = minZ.toInt() until maxZ.toInt()

/** `null` if vanilla would refuse the `/fill` for [maxBlocks]. */
internal fun fillBox(from: ParsedBlockPos, to: ParsedBlockPos, maxBlocks: Int): BoundingBox? {
    val box = blockBox(from, to)
    return if (box.volume > maxBlocks) null else box
}

/**
 * Destination of `/clone` only.
 *
 * Source is read-only and must not be captured.
 */
internal fun cloneDestBox(
    srcFrom: ParsedBlockPos,
    srcTo: ParsedBlockPos,
    dstOrigin: ParsedBlockPos,
    maxBlocks: Int,
): BoundingBox? {
    val src = blockBox(srcFrom, srcTo)
    if (src.volume > maxBlocks) return null
    return src.shift(dstOrigin.x - src.minX, dstOrigin.y - src.minY, dstOrigin.z - src.minZ)
}
