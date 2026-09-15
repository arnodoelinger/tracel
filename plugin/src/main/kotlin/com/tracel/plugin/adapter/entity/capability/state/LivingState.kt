package com.tracel.plugin.adapter.entity.capability.state

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity

/**
 * Health, air, AI, potions.
 *
 * @see [LivingEntity]
 */
internal object LivingState : InPlaceState {
    private const val DEAD_HEALTH = 0.0

    override fun apply(live: Entity, ghost: Entity) {
        if (live !is LivingEntity || ghost !is LivingEntity) return
        runCatching {
            val health = ghost.health
            val ceiling = live.getAttribute(Attribute.MAX_HEALTH)?.value ?: health

            // Health of 0 is a dead body: restoring it plays the death animation and dumps
            // loot again.
            if (health > DEAD_HEALTH) live.health = health.coerceAtMost(ceiling)
        }
        runCatching { live.remainingAir = ghost.remainingAir }
        runCatching { live.setAI(ghost.hasAI()) }
        runCatching {
            for (effect in live.activePotionEffects) live.removePotionEffect(effect.type)
            for (effect in ghost.activePotionEffects) live.addPotionEffect(effect)
        }
    }
}
