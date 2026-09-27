package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.entity.Entity
import org.bukkit.entity.Tameable

/**
 * Owner and tamed flag.
 *
 * @see [Tameable]
 */
internal object TameableState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Tameable || ghost !is Tameable) return
        runCatching {
            live.isTamed = ghost.isTamed
            live.owner = ghost.owner
        }
    }
}
