package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.Observes
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import org.bukkit.entity.Player
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType

/**
 * Inventory click listener.
 *
 * Diff after the click; never interpret click types.
 */
class InventoryClickListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onClick(event: InventoryClickEvent) {
        // Only CraftItemEvent is CraftCaptureListener
        if (event is CraftItemEvent) return

        // Matrix is net-zero for the player; the craft listener reads the grid
        if (event.slotType == InventoryType.SlotType.CRAFTING) return

        // InventoryCreativeEvent extends InventoryClickEvent; otherwise onCreative double-diffs
        if (event is InventoryCreativeEvent) return

        val player = event.whoClicked as? Player ?: return
        val view = event.view
        material.scheduleReconcile(
            player = player,
            inventories = listOfNotNull(view.topInventory, view.bottomInventory)
        )
    }

    @Observes
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        material.scheduleReconcile(
            player = player,
            inventories = listOfNotNull(view.topInventory, view.bottomInventory)
        )
    }

    @Observes
    fun onCreative(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        material.scheduleReconcile(
            player = player,
            inventories = listOfNotNull(view.topInventory, view.bottomInventory)
        )
    }

    @Observes
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        material.scheduleReconcile(
            player = player,
            inventories = listOfNotNull(event.view.topInventory, player.inventory)
        )
    }
}
