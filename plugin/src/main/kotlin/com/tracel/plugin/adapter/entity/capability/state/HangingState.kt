package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging

/**
 * Which wall a hanging is nailed to.
 *
 * Teleporting a painting moves the nail. Facing is the only setter that
 * keeps the art on the block it was recorded against.
 *
 * @see [Hanging]
 */
internal object HangingState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Hanging || ghost !is Hanging) return
        runCatching { live.setFacingDirection(ghost.facing, true) }
    }
}
