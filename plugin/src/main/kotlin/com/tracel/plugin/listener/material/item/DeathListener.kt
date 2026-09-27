package com.tracel.plugin.listener.material.item

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.addTo
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.entity.HitActor
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack

/** Death cargo listener. */
class DeathListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        material.died(player.uniqueId)
        if (!player.isLedgeredHolder()) return

        val dropped = event.drops.totals()
        if (dropped.isEmpty()) return

        val holder = HolderId.Player(player.uniqueId)
        val at = player.location
        val killer = player.killer?.let { HolderId.Player(it.uniqueId) }

        material.releasing(
            releases = listOf(BlockRelease(holder, at.world, at.blockX, at.blockY, at.blockZ, dropped)),
            cause = CauseKind.PLAYER_ACTION,
            causedBy = killer ?: holder,
            at = player.toBlockPos(),
        )
    }

    @Observes
    fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity is Player) return
        if (event.drops.isEmpty()) return

        val entity = event.entity
        val at = entity.location
        val killer = entity.killer?.let { HolderId.Player(it.uniqueId) } ?: HitActor.of(entity)

        material.releasing(
            releases = listOf(
                BlockRelease(
                    HolderId.Entity(entity.uniqueId),
                    at.world,
                    at.blockX,
                    at.blockY,
                    at.blockZ
                )
            ),
            cause = if (killer != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = killer,
            at = entity.toBlockPos(),
        )
    }

    private fun List<ItemStack>.totals(): Map<ItemKey, Long> {
        val out = HashMap<ItemKey, Long>()
        for (stack in this) stack.addTo(out)
        return out
    }
}
