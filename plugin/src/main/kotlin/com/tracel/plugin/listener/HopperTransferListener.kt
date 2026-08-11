package com.tracel.plugin.listener

import com.tracel.model.id.Quantity
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import com.tracel.plugin.convert.toItemKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val quantity = Quantity(event.item.amount.toLong())

        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    services.ledger.move(source, destination, itemKey, quantity, services.nextTxn())
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked material moved $source -> $destination, not recorded", e)
            }
        }
    }
}
