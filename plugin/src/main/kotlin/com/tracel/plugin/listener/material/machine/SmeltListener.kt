package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.block.cargoTotals
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import org.bukkit.block.Block
import org.bukkit.event.Cancellable
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import java.util.concurrent.ConcurrentHashMap

/** Smelt event listener. */
class SmeltListener(services: TracelServices) : TracelListener(services) {
    private val queued = ConcurrentHashMap.newKeySet<HolderId>()

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

        if (!queued.add(holder)) return
        later(block.location) {
            queued.remove(holder)

            if (material.reconcilePending(holder)) return@later
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
