package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.isLedgeredHolder
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.inventory.InventoryHolder

/** Hand mutations listener. */
class HandMutationListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onConsume(event: PlayerItemConsumeEvent) = afterTick(event.player)

    @Observes(ignoreCancelled = false)
    fun onItemBreak(event: PlayerItemBreakEvent) = afterTick(event.player)

    @Observes
    fun onEditBook(event: PlayerEditBookEvent) = afterTick(event.player)

    @Observes
    fun onBucketEntity(event: PlayerBucketEntityEvent) = afterTick(event.player)

    @Observes
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) = afterTick(event.player)

    @Observes
    fun onBucketFill(event: PlayerBucketFillEvent) = afterTick(event.player)

    @Observes
    fun onLeash(event: PlayerLeashEntityEvent) = afterTick(event.player)

    @Observes
    fun onUnleash(event: PlayerUnleashEntityEvent) = afterTick(event.player)

    @Observes
    fun onTakeLecternBook(event: PlayerTakeLecternBookEvent) {
        val player = event.player
        val lectern = event.lectern.block
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = HolderId.Player(player.uniqueId),
            blocks = listOf(lectern),
        )
        val inventory = (lectern.getState(false) as? InventoryHolder)?.inventory
        material.scheduleReconcile(player, listOfNotNull(inventory, player.inventory))
    }

    @Observes
    fun onGameMode(event: PlayerGameModeChangeEvent) {
        material.scheduleRebaseline(event.player)
    }

    private fun afterTick(player: Player) {
        if (!player.isLedgeredHolder()) return
        material.scheduleReconcile(player)
    }
}
