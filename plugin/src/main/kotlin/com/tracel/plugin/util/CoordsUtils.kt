package com.tracel.plugin.util

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.*

/**
 * Where `~` resolves.
 *
 * `null` sender origin (console / RCON) may only use absolute coordinates.
 */
internal data class CommandOrigin(val x: Double, val y: Double, val z: Double)

/** Converts the player's current location into a [CommandOrigin]. */
internal fun Player.toCommandOrigin(): CommandOrigin {
    val loc = location
    return CommandOrigin(loc.x, loc.y, loc.z)
}

/** Explosion origin. */
internal data class ExplosionOrigin(val world: UUID, val x: Double, val y: Double, val z: Double)

/** Location of an explosion. */
internal fun Location.toExplosionOrigin(): ExplosionOrigin? {
    val world = world ?: return null
    return ExplosionOrigin(world.uid, x, y, z)
}
