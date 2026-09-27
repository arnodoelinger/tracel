package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.flow.DESTROYED_SINK
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.InventoryHolder

/** Hand mutations listener. */
@Unstable
class HandMutationListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onConsume(event: PlayerItemConsumeEvent) = afterTick(event.player)

    @Observes(ignoreCancelled = false)
    fun onItemBreak(event: PlayerItemBreakEvent) {
        val player = event.player
        if (!player.isLedgeredHolder()) return
        val holder = HolderId.Player(player.uniqueId)
        val itemKey = event.brokenItem.toItemKey()
        material.adjust(holder, itemKey, -1L)
        material.moved(CauseKind.PLAYER_ACTION, holder, itemKey, holder, DESTROYED_SINK, 1L, at = player.toBlockPos())
    }

    @Observes(ignoreCancelled = false)
    fun onEgg(event: PlayerInteractEvent) {
        if (event.item?.type?.name?.endsWith("_SPAWN_EGG") == true) afterTick(event.player)
    }

    @Observes
    fun onEggOnMob(event: PlayerInteractEntityEvent) {
        val hand = event.player.inventory.getItem(event.hand)
        if (hand.type.name.endsWith("_SPAWN_EGG")) afterTick(event.player)
    }

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
