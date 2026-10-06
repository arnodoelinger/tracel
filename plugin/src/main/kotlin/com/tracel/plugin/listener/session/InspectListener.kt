package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.annotations.Unstable
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent

@Unstable
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

        services.lookup.inspect(player, block)
    }

    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onQuit(event: PlayerQuitEvent) {
        services.inspectors.clear(event.player.uniqueId)
    }
}
