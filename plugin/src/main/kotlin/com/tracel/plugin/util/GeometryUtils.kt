package com.tracel.plugin.util

import com.tracel.engine.log.LookupRegion
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.command.args.LookupScope
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import kotlin.math.floor
import kotlin.math.roundToInt

/** Chunk this block sits in. Same key for every block in that chunk, whatever the height. */
internal fun BlockPos.regionKey(): Any = Triple(world, x shr 4, z shr 4)

/**
 * Chunk this holder sits in, when it has coordinates.
 *
 * A holder with no block is its own group — do not mix it with a chunk of something else.
 */
internal fun HolderId.regionKey(): Any = when (this) {
    is HolderId.Block -> Triple(world, x shr 4, z shr 4)
    is HolderId.PlacedBlock -> Triple(world, x shr 4, z shr 4)
    else -> this
}

/**
 * Vanilla hanging origin: center of the support, offset half a block when
 * [wide] / [tall] is even.
 *
 * Wrong here and a 2 x 1 painting sits on the neighboring block. Lol.
 */
internal fun anchorPoint(
    x: Double,
    y: Double,
    z: Double,
    facing: BlockFace,
    wide: Int,
    tall: Int,
): Triple<Double, Double, Double> {
    val across = centeringOffset(wide)
    val up = centeringOffset(tall)
    val sideways = counterClockwise(facing)
    return Triple(
        floor(x - across * sideways.modX) + 0.5,
        floor(y - up) + 0.5,
        floor(z - across * sideways.modZ) + 0.5,
    )
}

/** Cardinal (or up / down) from recorded yaw / pitch. Hangings only accept these. */
internal fun facingFromPose(yaw: Float, pitch: Float): BlockFace = when {
    pitch <= -45f -> BlockFace.UP
    pitch >= 45f -> BlockFace.DOWN
    else -> facingFromYaw(yaw)
}

/**
 * Nearest of North / South / East / West from yaw.
 *
 * Minecraft yaw 0 is South.
 */
internal fun facingFromYaw(yaw: Float): BlockFace {
    val normalized = ((yaw % 360f) + 360f) % 360f
    return when ((normalized / 90f).roundToInt() % 4) {
        1 -> BlockFace.WEST
        2 -> BlockFace.NORTH
        3 -> BlockFace.EAST
        else -> BlockFace.SOUTH
    }
}

/** Packs a block coordinate into a `Long`: 26 bits [x], 26 bits [z], 12 bits [y]. */
internal fun packed(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

/** X out of a key from [packed]. */
internal fun unpackX(key: Long): Int = (key shr 38).toInt()

/** Z out of a key from [packed]. */
internal fun unpackZ(key: Long): Int = (key shl 26 shr 38).toInt()

/** Y out of a key from [packed]. */
internal fun unpackY(key: Long): Int = (key shl 52 shr 52).toInt()

/** Packs a chunk coordinate into a `Long`: 32 bits [x], 32 bits [z]. */
internal fun chunkKey(x: Int, z: Int): Long = ((x shr 4).toLong() shl 32) or ((z shr 4).toLong() and 0xffffffffL)

/** X out of a key from [chunkKey]. */
internal fun chunkKeyX(key: Long): Int = (key shr 32).toInt()

/** Z out of a key from [chunkKey]. */
internal fun chunkKeyZ(key: Long): Int = key.toInt()

/** Converts a `Bukkit` [World] instance to a domain [WorldId]. */
fun World?.toWorldId(): WorldId? = this?.uid?.let(::WorldId)

/** Creates a [LookupRegion] relative to this [Location]. */
fun Location.toLookupRegion(
    scope: LookupScope?,
    horizontalOnly: Boolean = false
): LookupRegion? {
    if (scope == null) return null
    val targetWorld = world ?: return null

    val chunkX = blockX shr 4
    val chunkZ = blockZ shr 4

    val (chunkRadius, blockRadius) = when (scope) {
        is LookupScope.Blocks -> Pair((scope.radius shr 4) + 1, scope.radius)
        is LookupScope.Chunks -> Pair(scope.radius, null)
        LookupScope.CurrentChunk -> Pair(0, null)
    }

    val (minY, maxY) = if (blockRadius != null && !horizontalOnly) {
        Pair(blockY - blockRadius, blockY + blockRadius)
    } else {
        Pair(Int.MIN_VALUE, Int.MAX_VALUE)
    }

    return LookupRegion(
        WorldId(targetWorld.uid),
        minChunkX = chunkX - chunkRadius,
        maxChunkX = chunkX + chunkRadius,
        minChunkZ = chunkZ - chunkRadius,
        maxChunkZ = chunkZ + chunkRadius,
        minX = blockRadius?.let { blockX - it } ?: Int.MIN_VALUE,
        maxX = blockRadius?.let { blockX + it } ?: Int.MAX_VALUE,
        minY = minY,
        maxY = maxY,
        minZ = blockRadius?.let { blockZ - it } ?: Int.MIN_VALUE,
        maxZ = blockRadius?.let { blockZ + it } ?: Int.MAX_VALUE
    )
}

private fun centeringOffset(blocks: Int): Double = if (blocks % 2 == 0) 0.5 else 0.0

private fun counterClockwise(face: BlockFace): BlockFace = when (face) {
    BlockFace.NORTH -> BlockFace.WEST
    BlockFace.WEST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.EAST
    BlockFace.EAST -> BlockFace.NORTH
    else -> BlockFace.NORTH
}