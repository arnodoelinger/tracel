package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.capability.state.InPlaceState
import org.bukkit.entity.Entity
import org.bukkit.entity.Painting

/**
 * Painting art.
 *
 * @see Painting
 */
@Unstable
internal object PaintingArt : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Painting || ghost !is Painting) return
        runCatching { live.setArt(ghost.art, true) }
    }
}
