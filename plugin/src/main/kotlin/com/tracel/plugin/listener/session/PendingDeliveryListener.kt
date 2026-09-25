package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.rollback.material.deliverPending
import kotlinx.coroutines.launch
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent

/** Flush offline rollback deliveries on join; silent when the queue is empty. */
class PendingDeliveryListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onJoin(event: PlayerJoinEvent) = deliver(event.player)

    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onRespawn(event: PlayerPostRespawnEvent) = deliver(event.player)

    private fun deliver(player: Player) {
        services.scope.launch {
            val report = services.restorer.deliverPending(player)
            if (!report.fullyRestored) {
                player.sendMessage("Some rollback material queued for you while offline could not be fully delivered — check server logs.")
            }
        }
    }
}
