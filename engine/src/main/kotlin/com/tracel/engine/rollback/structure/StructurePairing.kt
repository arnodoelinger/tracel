package com.tracel.engine.rollback.structure

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import java.util.concurrent.ConcurrentHashMap

/**
 * @return the other half of the two-block object [shape] is part of, or `null`
 * if it stands alone.
 */
@Unstable
public fun structuralPartnerOf(at: BlockPos, shape: BlockShape): BlockPos? {
    val value = shape.data.value

    val pair = pairing.computeIfAbsent(value) { pairOf(materialOf(it)) }
    if (pair == Pair.NONE) return null
    val props = value.blockProperties()

    return when (pair) {
        Pair.NONE -> null
        Pair.CHEST -> chestPartner(at, props)
        Pair.BED -> bedPartner(at, props)
        Pair.VERTICAL -> verticalPartner(at, props)
        Pair.PISTON_HEAD -> props["facing"]?.let { at.towards(it.opposite()) }
        Pair.PISTON -> if (props["extended"] == "true") props["facing"]?.let { at.towards(it) } else null
    }
}

private enum class Pair { NONE, CHEST, BED, VERTICAL, PISTON_HEAD, PISTON }

private val pairing = ConcurrentHashMap<String, Pair>()

private fun materialOf(value: String): String {
    val end = value.indexOf('[').let { if (it < 0) value.length else it }
    val start = value.indexOf(':').let { if (it in 0 until end) it + 1 else 0 }
    return value.substring(start, end)
}

private fun pairOf(material: String): Pair = when {
    material.endsWith("chest") -> Pair.CHEST
    material.endsWith("_bed") -> Pair.BED
    material.endsWith("_door") -> Pair.VERTICAL
    material in DOUBLE_PLANTS -> Pair.VERTICAL
    material == "piston_head" -> Pair.PISTON_HEAD
    material == "piston" || material == "sticky_piston" -> Pair.PISTON
    else -> Pair.NONE
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
