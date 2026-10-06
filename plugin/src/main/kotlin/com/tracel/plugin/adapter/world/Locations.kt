package com.tracel.plugin.adapter.world

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.world.WorldId
import com.tracel.plugin.command.args.scope.LookupScope
import com.tracel.plugin.util.geometry.lookupRegion
import org.bukkit.Location
import org.bukkit.World

/** Converts a `Bukkit` [World] instance to a domain [WorldId]. */
fun World?.toWorldId(): WorldId? = this?.uid?.let(::WorldId)

/** Creates a [LookupRegion] relative to this [Location]. */
fun Location.toLookupRegion(
    scope: LookupScope?,
    horizontalOnly: Boolean = false
): LookupRegion? {
    if (scope == null) return null
    val targetWorld = world ?: return null
    return lookupRegion(WorldId(targetWorld.uid), blockX, blockY, blockZ, scope, horizontalOnly)
}
