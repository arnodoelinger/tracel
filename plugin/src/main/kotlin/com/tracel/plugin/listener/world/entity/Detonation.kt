package com.tracel.plugin.listener.world.entity

import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRemoveEvent

internal fun Entity.isMidDetonation(cause: EntityRemoveEvent.Cause): Boolean = when {
    this is TNTPrimed -> true
    this !is Creeper -> false
    cause == EntityRemoveEvent.Cause.EXPLODE -> true
    runCatching { isIgnited }.getOrDefault(false) -> true
    else -> diedInAnExplosion()
}

internal fun Entity.diedInAnExplosion(): Boolean = when (lastDamageCause?.cause) {
    EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
    EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
        -> true

    else -> false
}
