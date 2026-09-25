package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.cargoTotals
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import org.bukkit.block.Block
import org.bukkit.event.Cancellable
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.FurnaceBurnEvent

/** Smelt event listener. */
class SmeltListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.HIGHEST)
    fun holdCookWhileRestoring(event: BlockCookEvent) = holdWhileRestoring(event.block, event)

    @Observes(priority = Priority.HIGHEST)
    fun holdBurnWhileRestoring(event: FurnaceBurnEvent) = holdWhileRestoring(event.block, event)

    @Observes(priority = Priority.HIGHEST)
    fun holdBrewWhileRestoring(event: BrewEvent) = holdWhileRestoring(event.block, event)

    private fun holdWhileRestoring(block: Block, event: Cancellable) {
        if (services.frozen.isFrozen(block.toHolderId())) event.isCancelled = true
    }

    @Observes
    fun onCook(event: BlockCookEvent) = afterTick(event.block, CauseKind.CRAFT)

    @Observes
    fun onBurn(event: FurnaceBurnEvent) = afterTick(event.block, CauseKind.WORLD)

    @Observes
    fun onBrew(event: BrewEvent) = afterTick(event.block, CauseKind.CRAFT)

    private fun afterTick(block: Block, cause: CauseKind) {
        val holder = block.toHolderId()
        val at = block.toBlockPos()

        later(block.location) {
            val totals = block.cargoTotals()
            if (totals != null) material.reconcile(
                holder = holder,
                totals = totals,
                cause = cause,
                causedBy = null,
                at = at
            )
        }
    }
}
