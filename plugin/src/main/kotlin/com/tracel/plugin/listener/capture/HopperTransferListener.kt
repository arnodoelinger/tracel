package com.tracel.plugin.listener.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import com.tracel.plugin.convert.toItemKey
import kotlinx.coroutines.launch
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import java.util.logging.Level
import java.util.logging.Logger

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
    private val logger = Logger.getLogger(HopperTransferListener::class.java.name)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMoveItem(event: InventoryMoveItemEvent) {
        val source = event.source.toHolderId() ?: return
        val destination = event.destination.toHolderId() ?: return
        val itemKey = event.item.toItemKey()
        val quantity = event.item.amount.toLong()
        val deltas = listOf(InventoryDelta(source, itemKey, -quantity), InventoryDelta(destination, itemKey, quantity))
        val epochMillis = System.currentTimeMillis()

        services.scope.launch {
            try {
                services.atomically {
                    services.capture.record(deltas, epochMillis, CauseKind.HOPPER, causedBy = null)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked material moved $source -> $destination, not recorded", e)
            }
        }
    }
}
