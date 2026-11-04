package com.tracel.plugin.util.geometry

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A side of a block.
 *
 * @param modX how far one step this way moves along X
 * @param modZ how far one step this way moves along Z
 */
enum class Facing(val modX: Int, val modZ: Int) {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0),
    UP(0, 0),
    DOWN(0, 0),
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
    facing: Facing,
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
internal fun facingFromPose(yaw: Float, pitch: Float): Facing = when {
    pitch <= -45f -> Facing.UP
    pitch >= 45f -> Facing.DOWN
    else -> facingFromYaw(yaw)
}

/**
 * Nearest of North / South / East / West from yaw.
 *
 * Minecraft yaw 0 is South.
 */
internal fun facingFromYaw(yaw: Float): Facing {
    val normalized = ((yaw % 360f) + 360f) % 360f
    return when ((normalized / 90f).roundToInt() % 4) {
        1 -> Facing.WEST
        2 -> Facing.NORTH
        3 -> Facing.EAST
        else -> Facing.SOUTH
    }
}

private fun centeringOffset(blocks: Int): Double = if (blocks % 2 == 0) 0.5 else 0.0

private fun counterClockwise(face: Facing): Facing = when (face) {
    Facing.NORTH -> Facing.WEST
    Facing.WEST -> Facing.SOUTH
    Facing.SOUTH -> Facing.EAST
    Facing.EAST -> Facing.NORTH
    else -> Facing.NORTH
}
