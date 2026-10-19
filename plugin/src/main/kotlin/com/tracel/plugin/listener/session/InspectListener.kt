package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.annotations.Unstable
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot

/** The inspector. */
@Unstable
class InspectListener(services: TracelServices) : TracelListener(services) {
    private fun Player.inspecting(): Boolean = services.inspectors.isActive(uniqueId)

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) return
        val player = event.player
        if (!player.inspecting()) return

        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)

        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (event.hand != EquipmentSlot.HAND) return

        services.lookup.inspect(player, block)
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onUseEntity(event: PlayerInteractEntityEvent) {
        if (!event.player.inspecting()) return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        val entity = event.rightClicked
        services.lookup.inspect(event.player, entity.location.block, entity)
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onUseEntityAt(event: PlayerInteractAtEntityEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onStrike(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (!player.inspecting()) return
        event.isCancelled = true
        services.lookup.inspect(player, event.entity.location.block, event.entity)
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player ?: return
        if (player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onEmpty(event: PlayerBucketEmptyEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onFill(event: PlayerBucketFillEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onBucketEntity(event: PlayerBucketEntityEvent) {
        if (event.player.inspecting()) event.isCancelled = true
    }

    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onQuit(event: PlayerQuitEvent) {
        services.inspectors.clear(event.player.uniqueId)
    }
}
