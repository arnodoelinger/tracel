package com.tracel.plugin.listener.delivery

import com.tracel.plugin.TracelServices
import kotlinx.coroutines.launch
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Flushes [TracelServices.pendingDeliveries] the moment a player joins — the other half of
 * [com.tracel.plugin.rollback.PhysicalRestorer]'s offline delivery queue.
 *
 * Silent when nothing is queued: [PhysicalRestorer.deliverPending] does the cheap check first.
 */
class PendingDeliveryListener(private val services: TracelServices) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        services.scope.launch {
            val report = services.restorer.deliverPending(player)
            if (!report.fullyRestored) {
                player.sendMessage("Some rollback material queued for you while offline could not be fully delivered — check server logs.")
            }
        }
    }
}
