package com.tracel.engine.rollback.structure

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape

/**
 * @return the other half of the two-block object [shape] is part of, or `null`
 * if it stands alone.
 */
// TODO: rewrite; this must not exist
@Unstable
public fun structuralPartnerOf(at: BlockPos, shape: BlockShape): BlockPos? {
    val value = shape.data.value
    val material = value.substringBefore('[').substringAfter(':')
    val props = value.blockProperties()

    return when {
        material == "chest" || material == "trapped_chest" -> chestPartner(at, props)
        material.endsWith("_bed") -> bedPartner(at, props)
        material.endsWith("_door") -> verticalPartner(at, props)
        material in DOUBLE_PLANTS -> verticalPartner(at, props)
        material == "piston_head" -> props["facing"]?.let { at.towards(it.opposite()) }
        material == "piston" || material == "sticky_piston" ->
            if (props["extended"] == "true") props["facing"]?.let { at.towards(it) } else null

        else -> null
    }
}

private val DOUBLE_PLANTS = setOf(
    "sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony",
    "pitcher_plant", "pitcher_crop", "tall_seagrass", "small_dripleaf",
)

private fun chestPartner(at: BlockPos, props: Map<String, String>): BlockPos? {
    val facing = props["facing"] ?: return null
    val direction = when (props["type"]) {
        "left" -> facing.clockwise()
        "right" -> facing.counterClockwise()
        else -> return null
    }
    return at.towards(direction)
}

private fun bedPartner(at: BlockPos, props: Map<String, String>): BlockPos? {
    val facing = props["facing"] ?: return null
    return when (props["part"]) {
        "head" -> at.towards(facing.opposite())
        "foot" -> at.towards(facing)
        else -> null
    }
}

private fun verticalPartner(at: BlockPos, props: Map<String, String>): BlockPos? = when (props["half"]) {
    "upper" -> at.copy(y = at.y - 1)
    "lower" -> at.copy(y = at.y + 1)
    else -> null
}

private fun BlockPos.towards(direction: String): BlockPos = when (direction) {
    "north" -> copy(z = z - 1)
    "south" -> copy(z = z + 1)
    "east" -> copy(x = x + 1)
    "west" -> copy(x = x - 1)
    "up" -> copy(y = y + 1)
    "down" -> copy(y = y - 1)
    else -> this
}

private fun String.opposite(): String = when (this) {
    "north" -> "south"
    "south" -> "north"
    "east" -> "west"
    "west" -> "east"
    "up" -> "down"
    "down" -> "up"
    else -> this
}

private fun String.clockwise(): String = when (this) {
    "north" -> "east"
    "east" -> "south"
    "south" -> "west"
    "west" -> "north"
    else -> this
}

private fun String.counterClockwise(): String = when (this) {
    "north" -> "west"
    "west" -> "south"
    "south" -> "east"
    "east" -> "north"
    else -> this
}

private fun String.blockProperties(): Map<String, String> {
    val start = indexOf('[')
    if (start < 0) return emptyMap()
    val end = lastIndexOf(']')
    if (end <= start) return emptyMap()
    return substring(start + 1, end).split(',').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
    }.toMap()
}
