package com.tracel.plugin.adapter.entity.kind

import com.tracel.annotations.Unstable
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityPlaceEvent

/**
 * Who is responsible for this spawn.
 *
 * @see [SpawnReason]
 */
enum class SpawnKind {
    /** Egg, command, breeding already in the world. Log now. */
    Immediate,

    /** [SpawnReason.DEFAULT]. Wait for [EntityPlaceEvent]. */
    Placed,

    /** Natural, portal, shoulder. Not ours. */
    World,
}

/**
 * Maps `Paper`'s reason onto [SpawnKind].
 *
 * Unknown reasons are [SpawnKind.World].
 */
@Unstable
fun SpawnReason?.kind(): SpawnKind = when (this) {
    SpawnReason.DEFAULT -> SpawnKind.Placed
    SpawnReason.COMMAND,
    SpawnReason.CUSTOM,
    SpawnReason.SPAWNER_EGG,
    SpawnReason.DISPENSE_EGG,
    SpawnReason.EGG,
    SpawnReason.BREEDING,
    SpawnReason.DUPLICATION,
    SpawnReason.BUCKET,
    SpawnReason.BUILD_SNOWMAN,
    SpawnReason.BUILD_IRONGOLEM,
    SpawnReason.BUILD_COPPERGOLEM,
    SpawnReason.BUILD_WITHER,
    SpawnReason.CURED,
    SpawnReason.SHEARED,
        -> SpawnKind.Immediate
    else -> SpawnKind.World
}

/**
 * True when somebody made this spawn: egg, command, breeding.
 *
 * Natural wildlife is `false`.
 */
fun shouldLogSpawn(reason: SpawnReason?): Boolean = reason.kind() != SpawnKind.World
