package com.tracel.plugin.listener.material.recipe

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.flow.DESTROYED_SINK
import io.papermc.paper.event.block.CompostItemEvent
import io.papermc.paper.event.entity.EntityCompostItemEvent
import org.bukkit.entity.Player

/**
 * Compost listener.
 *
 * Composter has no inventory, so hopper move and click diff never see the consume.
 * - Hopper: burn after [CompostItemEvent]
 * - Player: [EntityCompostItemEvent] then the hand next tick
 */
class CompostListener(services: TracelServices) : TracelListener(services) {
    @Observes(ignoreCancelled = false)
    fun onHopperCompost(event: CompostItemEvent) {
        if (event is EntityCompostItemEvent) return
        val item = event.item
        if (item.type.isAir || item.amount <= 0) return
        val block = event.block
        shape.reread(ActionKind.BLOCK_CHANGE, CauseKind.WORLD, null, listOf(block))
        material.moved(
            cause = CauseKind.WORLD,
            causedBy = null,
            itemKey = item.toItemKey(),
            from = block.toHolderId(),
            to = DESTROYED_SINK,
            quantity = item.amount.toLong(),
        )
    }

    @Observes
    fun onEntityCompost(event: EntityCompostItemEvent) {
        val player = event.entity as? Player ?: return
        material.scheduleReconcile(player)
    }
}
