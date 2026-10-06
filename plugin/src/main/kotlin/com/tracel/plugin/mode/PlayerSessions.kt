package com.tracel.plugin.mode

import com.tracel.annotations.Observes
import com.tracel.engine.actor.ActorFacts
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent

/** Writes down when each player opens and lets go of a container. */
internal class PlayerSessions(private val writes: ActorWrites, private val facts: ActorFacts) : Listener {
    @Observes
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player.uniqueId
        val millis = System.currentTimeMillis()
        writes.submit { facts.openVisit(player, millis) }
    }

    @Observes(ignoreCancelled = false)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player.uniqueId
        val millis = System.currentTimeMillis()
        writes.submit { facts.closeVisit(player, millis) }
    }
}
