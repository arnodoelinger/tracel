package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.entity.Entity

/**
 * Fire, freeze, name, glow, etc. All entity states.
 *
 * @see [Entity]
 */
internal object BaseEntityState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        runCatching {
            live.fireTicks = ghost.fireTicks
            live.freezeTicks = ghost.freezeTicks
            live.isInvulnerable = ghost.isInvulnerable
            live.isGlowing = ghost.isGlowing
            live.isSilent = ghost.isSilent
            live.customName(ghost.customName())
            live.isCustomNameVisible = ghost.isCustomNameVisible
        }
    }
}
