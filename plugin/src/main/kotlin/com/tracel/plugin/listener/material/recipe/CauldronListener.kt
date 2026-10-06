package com.tracel.plugin.listener.material.recipe

import com.tracel.annotations.Observes
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import org.bukkit.entity.Player
import org.bukkit.event.block.CauldronLevelChangeEvent

/**
 * Cauldron listener.
 *
 * Bottle fill and other actions with the hand.
 */
class CauldronListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onLevelChange(event: CauldronLevelChangeEvent) {
        val player = event.entity as? Player
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = if (player != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = player?.let { HolderId.Player(it.uniqueId) },
            blocks = listOf(event.block),
        )
        if (player == null) return
        material.scheduleReconcile(player)
    }
}
