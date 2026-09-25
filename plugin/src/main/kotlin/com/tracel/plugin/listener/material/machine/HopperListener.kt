package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.listener.TracelListener
import org.bukkit.block.Crafter
import org.bukkit.event.inventory.InventoryMoveItemEvent

/** Hopper transfer listener. */
class HopperListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.HIGHEST) // A monitor cannot cancel
    fun holdWhileRestoring(event: InventoryMoveItemEvent) {
        val source = event.source.toHolderId()
        val destination = event.destination.toHolderId()
        if (source != null && services.frozen.isFrozen(source) || destination != null && services.frozen.isFrozen(destination)) {
            event.isCancelled = true
        }
    }

    @Observes
    fun onMoveItem(event: InventoryMoveItemEvent) {
        if (event.source.holder is Crafter) return
        val source = event.source.toHolderId() ?: return
        val destination = event.destination.toHolderId() ?: return
        val at = event.destination.location ?: event.source.location ?: return
        material.settleLater(at, CauseKind.HOPPER, mapOf(source to event.source, destination to event.destination))
    }
}
