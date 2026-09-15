package com.tracel.plugin.adapter.entity.capability.state

import io.papermc.paper.entity.CollarColorable
import org.bukkit.entity.Entity

/**
 * Collar dye.
 *
 * @see [CollarColorable]
 */
internal object CollarState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is CollarColorable || ghost !is CollarColorable) return
        runCatching { live.collarColor = ghost.collarColor }
    }
}
