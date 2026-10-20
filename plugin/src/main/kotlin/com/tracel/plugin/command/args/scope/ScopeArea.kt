package com.tracel.plugin.command.args.scope

import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import org.bukkit.Location
import org.bukkit.World

/**
 * Where a scope looks, as the API says it: [scope] around [center], at every height with [horizontalOnly]; failing
 * that all of [world]; failing that everywhere, `null`.
 */
internal fun areaOf(center: Location?, scope: LookupScope?, horizontalOnly: Boolean, world: World?): LookupArea? {
    val place = center?.let(BlockPlace::of)
    if (scope == null || place == null) return world?.let { LookupArea.World(it.uid) }
    return when (scope) {
        is LookupScope.Blocks -> LookupArea.Around(place, scope.radius.coerceAtLeast(0), horizontalOnly)
        is LookupScope.Chunks -> LookupArea.Chunks(place, scope.radius)
        LookupScope.CurrentChunk -> LookupArea.Chunks(place, 0)
        LookupScope.CurrentBlock ->
            if (horizontalOnly) LookupArea.Around(place, 0, anyHeight = true)
            else LookupArea.Cube(place.copy(y = place.y - 1), place)
    }
}
