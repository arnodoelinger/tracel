package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.entity.Entity
import org.bukkit.entity.Sittable

/**
 * Sit / stand. Wolves, cats, and whoever else.
 *
 * @see [Sittable]
 */
internal object SittableState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Sittable || ghost !is Sittable) return
        runCatching { live.isSitting = ghost.isSitting }
    }
}
