package com.tracel.plugin.listener.capture

import com.tracel.annotations.CauseKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import com.tracel.plugin.convert.toItemKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent

/**
 * Captures hopper-style item transfers — the simplest, least ambiguous inventory movement
 * `Bukkit` fires: a resolved [org.bukkit.inventory.ItemStack] moving from one resolved
 * inventory to another, no click-state-machine to interpret like `InventoryClickEvent` has.
 *
 * `MONITOR` + `ignoreCancelled`: only record what will actually happen, after every other
 * plugin's had a chance to cancel it — the same ordering [com.tracel.annotations.Observes]
 * documents as its default.
 */
class HopperTransferListener(private val services: TracelServices) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMoveItem(event: InventoryMoveItemEvent) {
        val source = event.source.toHolderId() ?: return
        val destination = event.destination.toHolderId() ?: return

        services.gate.move(
            cause = CauseKind.HOPPER,
            causedBy = null,
            epochMillis = System.currentTimeMillis(),
            itemKey = event.item.toItemKey(),
            from = source,
            to = destination,
            quantity = event.item.amount.toLong(),
        )
    }
}
