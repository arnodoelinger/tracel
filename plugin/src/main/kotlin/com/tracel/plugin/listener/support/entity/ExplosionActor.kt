package com.tracel.plugin.listener.support.entity

import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import org.bukkit.entity.Creeper
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Ghast
import org.bukkit.entity.Entity
import org.bukkit.entity.Explosive
import org.bukkit.entity.Fireball
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Wither
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.entity.EntityExplodeEvent

private const val MAX_IGNITION_CHAIN_DEPTH = 16

/** Whether it's a blast source, i.e. a source of explosion damage. */
fun Entity?.isBlastSource(): Boolean = when (this) {
    is Explosive, is Creeper, is EnderCrystal, is Ghast -> true
    else -> false
}

/**
 * Shared blast actor.
 *
 * Crater blocks see [EntityExplodeEvent]; killed entities are damaged first.
 */
fun TracelServices.explosionActor(entity: Entity, depth: Int = 0): HolderId? {
    if (depth >= MAX_IGNITION_CHAIN_DEPTH) return null
    return when (entity) {
        is TNTPrimed -> igniterOf(entity, depth)

        // Unlit creepers are absent from the tracker; only flint ignitions are recorded
        is Creeper -> redstoneTriggers.creeperIgnitedBy(entity.uniqueId)
        is Fireball -> when (val shooter = entity.shooter) {
            is Player -> HolderId.Player(shooter.uniqueId)
            is Entity -> explosionActor(shooter, depth + 1)
            else -> HitActor.builderOf(entity)
        }
        is Player -> HolderId.Player(entity.uniqueId)
        is EnderCrystal, is ExplosiveMinecart -> HitActor.of(entity) ?: redstoneTriggers.recentExplosionNear(entity.location)
        is Wither -> HitActor.builderOf(entity)
        else -> null
    }
}

private fun TracelServices.igniterOf(tnt: TNTPrimed, depth: Int): HolderId? {
    if (depth >= MAX_IGNITION_CHAIN_DEPTH) return null
    return when (val source = tnt.source) {
        null -> redstoneTriggers.recentPressNear(tnt.world, tnt.location.blockX, tnt.location.blockY, tnt.location.blockZ)
            ?: HitActor.builderOf(tnt)
            ?: redstoneTriggers.recentExplosionNear(tnt.location)
        is Player -> HolderId.Player(source.uniqueId)
        else -> explosionActor(source, depth + 1)
    }
}
