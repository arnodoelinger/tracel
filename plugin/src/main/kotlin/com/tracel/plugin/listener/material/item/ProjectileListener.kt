package com.tracel.plugin.listener.material.item

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.kind.isReclaimable
import com.tracel.plugin.adapter.entity.kind.projectileItemKey
import com.tracel.plugin.adapter.entity.kind.shouldLogProjectile
import com.tracel.plugin.adapter.entity.toPlacedEntityId
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.entity.LiveProjectile
import com.tracel.plugin.listener.support.flow.CREATIVE_SINK
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.projectiles.BlockProjectileSource

/**
 * Projectile listener.
 *
 * In-flight projectile is the item [HolderId.PlacedEntity].
 *
 * Unstable.
 */
@Unstable
class ProjectileListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onLaunch(event: ProjectileLaunchEvent) {
        if (restoring) return
        val projectile = event.entity
        if (!projectile.shouldLogProjectile()) return
        val itemKey = projectile.projectileItemKey() ?: return
        val holder = projectile.toPlacedEntityId()
        services.groundWhereabouts.remember(projectile)

        when (val shooter = projectile.shooter) {
            is Player -> fromPlayer(shooter, projectile, itemKey, holder)
            is BlockProjectileSource -> fromBlock(projectile, itemKey, holder)
            null -> fromBlock(projectile, itemKey, holder)
            else -> Unit
        }
    }

    private fun fromPlayer(shooter: Player, projectile: Projectile, itemKey: ItemKey, holder: HolderId.PlacedEntity) {
        val playerHolder = HolderId.Player(shooter.uniqueId)
        if (!shooter.isLedgeredHolder()) return

        // Creative throws cost nothing
        if (shooter.gameMode == GameMode.CREATIVE) return

        // Event-booked; differ must be told or the next inventory read reports the item leaving twice
        material.adjust(
            holder = playerHolder,
            itemKey = itemKey,
            delta = -1L
        )
        material.positioned(
            cause = CauseKind.PLAYER_ACTION,
            causedBy = playerHolder,
            at = projectile.location,
            deltas = listOf(InventoryDelta(holder, itemKey, 1L), InventoryDelta(playerHolder, itemKey, -1L)),
        )
        LiveProjectile.add(projectile.uniqueId)
    }

    private fun fromBlock(projectile: Projectile, itemKey: ItemKey, holder: HolderId.PlacedEntity) {
        val loc = projectile.location
        val claimed = services.blockDrops.claim(loc.world, loc.x, loc.y, loc.z, itemKey, 1L, holder)
        if (claimed > 0L) LiveProjectile.add(projectile.uniqueId)
    }

    @Observes
    fun onPickup(event: PlayerPickupArrowEvent) {
        if (restoring) return
        val arrow = event.arrow

        // Creative-only arrows were never booked; moving them out would be rejected; fall through to reconcile mint
        if (!arrow.isReclaimable()) return
        val itemKey = arrow.projectileItemKey() ?: return
        val player = event.player
        val playerHolder = HolderId.Player(player.uniqueId)
        val holder = arrow.toPlacedEntityId()
        services.groundWhereabouts.remember(arrow)

        if (!player.isLedgeredHolder()) {
            material.moved(
                cause = CauseKind.PLAYER_ACTION,
                causedBy = playerHolder,
                itemKey = itemKey,
                from = holder,
                to = CREATIVE_SINK,
                quantity = 1L
            )
            return
        }
        LiveProjectile.remove(arrow.uniqueId)
        material.adjust(
            holder = playerHolder,
            itemKey = itemKey,
            delta = 1L
        )
        material.moved(
            cause = CauseKind.PLAYER_ACTION,
            causedBy = playerHolder,
            itemKey = itemKey,
            from = holder,
            to = playerHolder,
            quantity = 1L
        )
    }

    @Observes(ignoreCancelled = false)
    fun onRemove(event: EntityRemoveEvent) {
        if (restoring) return
        val projectile = event.entity as? Projectile ?: return
        if (projectile.uniqueId !in LiveProjectile) return
        when (event.cause) {
            EntityRemoveEvent.Cause.PICKUP,
            EntityRemoveEvent.Cause.UNLOAD,
            EntityRemoveEvent.Cause.PLAYER_QUIT,
                -> return

            else -> Unit
        }
        LiveProjectile.remove(projectile.uniqueId)
        services.groundWhereabouts.remember(projectile)
        material.released(
            cause = CauseKind.WORLD,
            causedBy = null,
            from = projectile.toPlacedEntityId(),
            to = HolderId.Sink(SinkKind.UNATTRIBUTED),
        )
    }

}
