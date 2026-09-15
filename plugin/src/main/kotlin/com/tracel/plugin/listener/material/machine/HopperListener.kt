package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.listener.TracelListener
import org.bukkit.event.inventory.InventoryMoveItemEvent

/** Hopper transfer listener. */
class HopperListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.HIGHEST) // A monitor cannot cancel
    fun holdWhileRestoring(event: InventoryMoveItemEvent) {
        val source = event.source.toHolderId()
        val destination = event.destination.toHolderId()
        if (services.frozen.isFrozen(source ?: return) || services.frozen.isFrozen(destination ?: return)) {
            event.isCancelled = true
        }
    }

    @Observes
    fun onMoveItem(event: InventoryMoveItemEvent) {
        val source = event.source.toHolderId() ?: return
        val destination = event.destination.toHolderId() ?: return
        // Both snapshots: booked from the event, never diffed
        material.hopped(
            cause = CauseKind.HOPPER,
            causedBy = null,
            from = source,
            to = destination,
            stack = event.item,
        )
    }
}
