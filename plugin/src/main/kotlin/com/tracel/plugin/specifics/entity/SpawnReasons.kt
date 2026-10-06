package com.tracel.plugin.specifics.entity

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.kind.SpawnKind
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason

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
