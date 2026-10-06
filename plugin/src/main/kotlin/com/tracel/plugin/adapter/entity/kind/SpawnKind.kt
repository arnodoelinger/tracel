package com.tracel.plugin.adapter.entity.kind

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
