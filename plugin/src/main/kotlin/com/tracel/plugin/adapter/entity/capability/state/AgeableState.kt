package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity

/**
 * Baby / adult age.
 *
 * @see [Ageable].
 */
internal object AgeableState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Ageable || ghost !is Ageable) return
        runCatching { live.age = ghost.age }
    }
}
