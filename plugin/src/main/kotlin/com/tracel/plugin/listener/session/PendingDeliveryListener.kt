package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.rollback.material.deliverPending
import com.tracel.plugin.rollback.structure.rescueJoined
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent

private const val RESPAWN_SETTLE_TICKS = 2L
private const val JOIN_SETTLE_TICKS = 20L

/** Flush offline rollback deliveries on join, and pull a player out of whatever a rollback built on them. */
class PendingDeliveryListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        deliver(player)
        later(player, JOIN_SETTLE_TICKS) { services.scope.launch { services.structureRestorer.rescueJoined(player) } }
    }

    // Folia never fires the post-respawn event; the player is back in the world a couple of ticks after this one
    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        material.respawned(player.uniqueId)
        later(player, RESPAWN_SETTLE_TICKS) { deliver(player) }
    }

    private fun deliver(player: Player) {
        services.scope.launch { services.restorer.deliverPending(player) }
    }
}
