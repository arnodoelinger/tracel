package com.tracel.plugin.listener.inspect

import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import com.tracel.plugin.convert.toPlacedBlockId
import com.tracel.plugin.lookup.describeHolder
import com.tracel.plugin.lookup.renderLookupResult
import kotlinx.coroutines.launch
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent

private const val PAGE_SIZE = 8

/** Inspect listener. */
class InspectListener(private val services: TracelServices) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val player = event.player
        if (!services.inspectors.isActive(player.uniqueId)) return

        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)

        val holder = if (block.state is Container) block.toHolderId() else block.toPlacedBlockId()
        showHistory(player, holder)
    }

    private fun showHistory(player: Player, holder: HolderId) {
        services.scope.launch {
            val results = services.atomically {
                services.log.query(LookupFilter(holders = setOf(holder), limit = PAGE_SIZE))
            }

            if (results.isEmpty()) {
                player.sendMessage("No history recorded for ${describeHolder(holder)} yet.")
                return@launch
            }

            player.sendMessage("History for ${describeHolder(holder)}:")
            for (txn in results) {
                renderLookupResult(txn).forEach(player::sendMessage)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        services.inspectors.clear(event.player.uniqueId)
    }
}
