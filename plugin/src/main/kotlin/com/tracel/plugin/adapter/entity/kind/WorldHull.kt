package com.tracel.plugin.adapter.entity.kind

import org.bukkit.entity.Entity

/**
 * What the world log does with this hull.
 *
 * @see [Entity]
 */
enum class WorldHull {
    /** Players and ground items. */
    Skip,

    /** Placed scenery. Logged whoever put it there. */
    Scenery,

    /** Mobs. Logged only when a person made them. */
    Creature,
}
