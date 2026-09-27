package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.Observes
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import org.bukkit.entity.Player
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.transientInputs
import com.tracel.plugin.adapter.block.toBlockPos
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.InventoryAction
import com.tracel.plugin.listener.support.drop.ContainerDrop
import com.tracel.plugin.adapter.item.toItemKey
import org.bukkit.inventory.CraftingInventory

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

        // Matrix is net-zero for the player; the craft listener reads the grid. Crafting is also a
        // furnace input or a brewing slot, and those clicks are moves like any other
        if (event.slotType == InventoryType.SlotType.CRAFTING && event.view.topInventory is CraftingInventory) return

        // InventoryCreativeEvent extends InventoryClickEvent; otherwise onCreative double-diffs
        if (event is InventoryCreativeEvent) return

        val player = event.whoClicked as? Player ?: return
        val view = event.view
        if ((event.action == InventoryAction.DROP_ALL_SLOT || event.action == InventoryAction.DROP_ONE_SLOT) && event.clickedInventory == view.topInventory) {
            val from = view.topInventory.toHolderId()
            val stack = event.currentItem
            if ((from is HolderId.Block || from is HolderId.Entity) && stack != null && !stack.type.isAir) {
                ContainerDrop.expect(player.uniqueId, from, stack.toItemKey())
            }
        }
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
    fun onOpen(event: InventoryOpenEvent) {
        val top = event.inventory
        if (top is CraftingInventory || top.transientInputs() != null) return
        val holder = top.toHolderId() ?: return
        if (holder !is HolderId.Block && holder !is HolderId.Entity) return
        material.seedOnOpen(holder, top.toItemTotals(), top.location?.block?.toBlockPos())
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
