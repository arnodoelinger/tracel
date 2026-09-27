package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.entity.Entity

/**
 * Copy one `Paper` capability from a deserialized ghost onto the hull that is
 * still standing.
 *
 * @see [Entity]
 */
internal fun interface InPlaceState {
    /** Apply capability. */
    fun apply(live: Entity, ghost: Entity)
}
