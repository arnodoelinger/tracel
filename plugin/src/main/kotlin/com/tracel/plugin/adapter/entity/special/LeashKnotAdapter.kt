package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.LeashHitch
import org.bukkit.util.BoundingBox

/**
 * Leash knot. Vanilla keeps one hitch per block and reuses it.
 *
 * @see LeashHitch
 */
@Unstable
internal object LeashKnotAdapter {
    fun isType(type: String): Boolean {
        val key = NamespacedKey.fromString(type) ?: return type.substringAfter(':') == "leash_knot"
        return key.key == "leash_knot"
    }

    fun at(world: World, loc: Location): Entity? = runCatching {
        world.getNearbyEntities(BoundingBox.of(loc.block))
            .firstOrNull { it is LeashHitch && it.isValid }
    }.getOrNull()
}
