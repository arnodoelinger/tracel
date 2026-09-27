package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.listener.TracelListener
import org.bukkit.block.Crafter
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.inventory.Inventory
import java.util.concurrent.ConcurrentHashMap

/** Hopper transfer listener. */
class HopperListener(services: TracelServices) : TracelListener(services) {
    private val seeding = ConcurrentHashMap.newKeySet<HolderId>()

    @Observes(priority = Priority.HIGHEST) // A monitor cannot cancel
    fun holdWhileRestoring(event: InventoryMoveItemEvent) {
        val source = event.source.toHolderId()
        val destination = event.destination.toHolderId()
        if (source != null && services.frozen.isFrozen(source) || destination != null && services.frozen.isFrozen(destination)) {
            event.isCancelled = true
        }
    }

    @Observes
    fun onMoveItem(event: InventoryMoveItemEvent) {
        if (event.source.holder is Crafter) return
        val source = event.source.toHolderId() ?: return
        val destination = event.destination.toHolderId() ?: return
        val at = event.destination.location ?: event.source.location ?: return
        val pos = BlockPos(WorldId(at.world.uid), at.blockX, at.blockY, at.blockZ)

        // Paper (for some reason, maybe for optimization) shrinks the source slot to the one item for the event's sake, so
        // no read now is the truth.
        val moved = event.item.toItemKey() to event.item.amount.toLong()
        val seedSource = !material.seeded(source) && seeding.add(source)
        val seedDestination = !material.seeded(destination) && seeding.add(destination)
        if (seedSource || seedDestination) {
            val from = event.source.real()
            val into = event.destination.real()
            later(at) {
                if (seedSource) material.seedOnOpen(source, from.toItemTotals().shifted(moved, +1), pos, baseline = true)
                if (seedDestination) material.seedOnOpen(destination, into.toItemTotals().shifted(moved, -1), pos, baseline = true)
                seeding -= source
                seeding -= destination
            }
        }
        material.settleLater(at, CauseKind.HOPPER, mapOf(source to event.source, destination to event.destination))
    }
}

private fun Inventory.real(): Inventory = runCatching { getHolder(false)?.inventory }.getOrNull() ?: this

private fun Map<ItemKey, Long>.shifted(moved: Pair<ItemKey, Long>, sign: Int): Map<ItemKey, Long> {
    val out = HashMap(this)
    val qty = (out[moved.first] ?: 0L) + sign * moved.second
    if (qty > 0L) out[moved.first] = qty else out.remove(moved.first)
    return out
}
