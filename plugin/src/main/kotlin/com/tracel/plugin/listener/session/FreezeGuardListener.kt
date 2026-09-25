package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.listener.TracelListener
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * Holds players and piles a rollback is moving, for the moment between the ledger committing and the
 * items physically leaving.
 */
class FreezeGuardListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.HIGHEST)
    fun holdClick(event: InventoryClickEvent) {
        if (frozenPlayer(event.whoClicked as? Player) || frozen(event.view.topInventory.toHolderId())) event.isCancelled = true
    }

    @Observes(priority = Priority.HIGHEST)
    fun holdDrag(event: InventoryDragEvent) {
        if (frozenPlayer(event.whoClicked as? Player) || frozen(event.view.topInventory.toHolderId())) event.isCancelled = true
    }

    @Observes(priority = Priority.HIGHEST)
    fun holdDrop(event: PlayerDropItemEvent) {
        if (frozenPlayer(event.player)) event.isCancelled = true
    }

    @Observes(priority = Priority.HIGHEST)
    fun holdSwap(event: PlayerSwapHandItemsEvent) {
        if (frozenPlayer(event.player)) event.isCancelled = true
    }

    @Observes(priority = Priority.HIGHEST)
    fun holdPickup(event: EntityPickupItemEvent) {
        val taker = event.entity
        val takerFrozen = if (taker is Player) frozenPlayer(taker) else frozen(HolderId.Entity(taker.uniqueId))
        if (takerFrozen || frozen(HolderId.ItemEntity(event.item.uniqueId))) event.isCancelled = true
    }

    private fun frozenPlayer(player: Player?): Boolean = player != null && frozen(HolderId.Player(player.uniqueId))

    private fun frozen(holder: HolderId?): Boolean = holder != null && services.frozen.isFrozen(holder)
}
