package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * The mouse wheel and the keys of a scrub: the hotbar is the timeline:
 * `F` plays it, `Q` ends it, and nothing else the player does reaches
 * the world.
 */
internal class ScrubListener(services: TracelServices) : TracelListener(services) {
    private val scrubs get() = services.scrubs

    // Keep as inspector

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onWheel(event: PlayerItemHeldEvent) {
        val scrub = scrubs.of(event.player) ?: return
        event.isCancelled = true
        scrub.scroll(event.newSlot, event.player.isSneaking)
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        val scrub = scrubs.of(event.player) ?: return
        event.isCancelled = true
        scrub.toggle()
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        if (scrubs.of(event.player) == null) return
        event.isCancelled = true
        scrubs.end(event.player)
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) return
        val scrub = scrubs.of(event.player) ?: return
        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        scrub.touched()
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) = held(event.player) { event.isCancelled = true }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) = held(event.player) { event.isCancelled = true }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onEntity(event: PlayerInteractEntityEvent) = held(event.player) { event.isCancelled = true }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onStrike(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        held(player) { event.isCancelled = true }
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) = held(event.player) { event.isCancelled = true }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onBucketFill(event: PlayerBucketFillEvent) = held(event.player) { event.isCancelled = true }

    @Observes(priority = Priority.MONITOR, ignoreCancelled = false)
    fun onChunk(event: PlayerChunkLoadEvent) {
        scrubs.of(event.player)?.sent(event.chunk.x, event.chunk.z)
    }

    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onWorld(event: PlayerChangedWorldEvent) {
        scrubs.drop(event.player.uniqueId)
    }

    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onQuit(event: PlayerQuitEvent) {
        scrubs.drop(event.player.uniqueId)
    }

    private inline fun held(player: Player, cancel: () -> Unit) {
        val scrub = scrubs.of(player) ?: return
        cancel()
        scrub.touched()
    }
}
