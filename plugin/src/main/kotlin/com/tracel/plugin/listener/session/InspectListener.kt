package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.cargoSlots
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.block.toPlacedBlockId
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.dialog.describeHolder
import com.tracel.plugin.dialog.renderLookupResult
import com.tracel.plugin.dialog.renderWorldChange
import com.tracel.plugin.util.blockPos
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent

// TODO: rewrite

private const val PAGE_SIZE = 8

class InspectListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val player = event.player
        if (!services.inspectors.isActive(player.uniqueId)) return

        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)

        val holder = if (block.cargoSlots() != null) block.toHolderId() else block.toPlacedBlockId()
        showHistory(player, holder)
    }

    private fun showHistory(player: Player, holder: HolderId) {
        services.scope.launch {
            val (txns, world) = services.reading {
                val txns = services.log.query(LookupFilter(holders = setOf(holder), limit = PAGE_SIZE))
                val pos = holder.blockPos()
                val world = if (pos == null) emptyList() else services.worldLog.at(pos, PAGE_SIZE)
                txns to world
            }

            val where = holder.blockPos()?.let { "${it.x},${it.y},${it.z}" } ?: describeHolder(holder)
            if (txns.isEmpty() && world.isEmpty()) {
                player.sendMessage("No history recorded at $where yet.")
                return@launch
            }

            player.sendMessage("History at $where:")
            val lines = txns.map { it.seq.raw to renderLookupResult(it) } +
                world.map { it.seq.raw to listOf(renderWorldChange(it)) }
            lines.sortedByDescending { it.first }
                .take(PAGE_SIZE)
                .flatMap { it.second }
                .forEach(player::sendMessage)
        }
    }

    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onQuit(event: PlayerQuitEvent) {
        services.inspectors.clear(event.player.uniqueId)
    }
}
