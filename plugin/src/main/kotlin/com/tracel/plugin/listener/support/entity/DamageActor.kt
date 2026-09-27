package com.tracel.plugin.listener.support.entity

import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.annotations.CauseKind
import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.event.entity.EntityDamageByBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent

internal data class DamageBlame(val who: HolderId?, val cause: CauseKind)

/** Damage blame. */
@Unstable
internal fun TracelServices.damageBlame(event: EntityDamageEvent): DamageBlame {
    val blast = event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
        event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
    if (event is EntityDamageByEntityEvent) {
        val who = attackerOf(event.damager)
        val cause = when {
            blast -> CauseKind.EXPLOSION
            who is HolderId.Player -> CauseKind.PLAYER_ACTION
            else -> CauseKind.ENTITY_ACTION
        }
        // Chain dry: proximity detonations (TNT nobody lit by hand) still live on the trigger tracker
        val fallback = if (blast && who == null) redstoneTriggers.recentExplosionNear(event.entity.location) else null
        return DamageBlame(who ?: fallback, cause)
    }
    if (event is EntityDamageByBlockEvent) {
        val block = event.damager
        val who = block?.let { ColumnCell.playerAt(it) }?.let(HolderId::Player)
        return DamageBlame(who, if (blast) CauseKind.EXPLOSION else causeFor(who))
    }
    // Environmental damage has no damager; the column is the only actor we have
    if (event.cause !in ENVIRONMENTAL) return DamageBlame(null, CauseKind.WORLD)
    val who = ColumnCell.playerWhoDisturbed(event.entity)?.let(HolderId::Player)
    return DamageBlame(who, causeFor(who))
}

private fun TracelServices.attackerOf(damager: Entity?): HolderId? = when (damager) {
    null -> null
    is Player -> HolderId.Player(damager.uniqueId)
    is Projectile -> when (val shooter = damager.shooter) {
        is Player -> HolderId.Player(shooter.uniqueId)
        is Entity -> attackerOf(shooter)
        else -> null
    }

    else -> explosionActor(damager)
        ?: (damager as? Tameable)?.owner?.uniqueId?.let(HolderId::Player)
        ?: HolderId.Entity(damager.uniqueId)
}

private fun causeFor(who: HolderId?) = if (who == null) CauseKind.WORLD else CauseKind.PLAYER_ACTION

private val ENVIRONMENTAL = setOf(
    EntityDamageEvent.DamageCause.FIRE,
    EntityDamageEvent.DamageCause.FIRE_TICK,
    EntityDamageEvent.DamageCause.LAVA,
    EntityDamageEvent.DamageCause.HOT_FLOOR,
    EntityDamageEvent.DamageCause.CONTACT,
    EntityDamageEvent.DamageCause.SUFFOCATION,
    EntityDamageEvent.DamageCause.DROWNING,
    EntityDamageEvent.DamageCause.FREEZE,
)
