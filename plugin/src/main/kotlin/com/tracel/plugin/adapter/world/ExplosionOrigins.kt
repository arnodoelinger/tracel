package com.tracel.plugin.adapter.world

import com.tracel.plugin.util.geometry.ExplosionOrigin
import java.util.*
import org.bukkit.Location

/** Location of an explosion. */
internal fun Location.toExplosionOrigin(): ExplosionOrigin? {
    val world = world ?: return null
    return ExplosionOrigin(world.uid, x, y, z)
}
