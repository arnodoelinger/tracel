package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.capability.state.InPlaceState
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame

/**
 * Item frame state.
 *
 * @see ItemFrame
 */
@Unstable
internal object ItemFrameState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is ItemFrame || ghost !is ItemFrame) return
        live.rotation = ghost.rotation
    }
}
