package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.addTo
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.concurrent.ExpiringMap
import java.util.*
import org.bukkit.Bukkit
import org.bukkit.GameEvent
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.CopperGolem
import org.bukkit.event.world.GenericGameEvent

/** Copper golems moving items in and out of chests. */
@Unstable
class CopperGolemListener(services: TracelServices) : TracelListener(services) {
    private class Visit(val golem: UUID, val chest: Map<ItemKey, Long>, val hand: Map<ItemKey, Long>)

    private val visits = ExpiringMap<HolderId.Block, Visit>(VISIT_MS)

    @Observes
    fun onGameEvent(event: GenericGameEvent) {
        if (restoring) return
        when (event.event) {
            GameEvent.CONTAINER_OPEN -> opened(event)
            GameEvent.CONTAINER_CLOSE -> closed(event)
        }
    }

    private fun opened(event: GenericGameEvent) {
        val golem = event.entity as? CopperGolem ?: return
        val block = event.location.block
        val chest = block.inventoryTotals() ?: return
        val holder = block.toHolderId()
        val pos = block.toBlockPos()
        material.seedOnOpen(holder, chest, pos, baseline = true)
        visits.put(holder, Visit(golem.uniqueId, chest, golem.handTotals()))
    }

    private fun closed(event: GenericGameEvent) {
        val block = event.location.block
        val holder = block.toHolderId()
        val visit = visits.remove(holder) ?: return
        val golem = Bukkit.getEntity(visit.golem) as? CopperGolem ?: return
        val chest = block.inventoryTotals() ?: return
        val hand = golem.handTotals()
        val by = HolderId.Entity(visit.golem)
        val pos = block.toBlockPos()

        for (key in visit.chest.keys + chest.keys) {
            val chestDelta = (chest[key] ?: 0L) - (visit.chest[key] ?: 0L)
            val handDelta = (hand[key] ?: 0L) - (visit.hand[key] ?: 0L)
            val moved = when {
                chestDelta < 0L && handDelta > 0L -> minOf(-chestDelta, handDelta)
                chestDelta > 0L && handDelta < 0L -> -minOf(chestDelta, -handDelta)
                else -> continue
            }
            val taken = moved > 0L
            material.adjust(holder, key, -moved)
            material.moved(
                cause = CauseKind.ENTITY_ACTION,
                causedBy = by,
                itemKey = key,
                from = if (taken) holder else by,
                to = if (taken) by else holder,
                quantity = if (taken) moved else -moved,
                at = pos,
            )
        }
    }

    private fun Block.inventoryTotals(): Map<ItemKey, Long>? =
        (getState(false) as? Container)?.inventory?.toItemTotals()

    private fun CopperGolem.handTotals(): Map<ItemKey, Long> {
        val totals = HashMap<ItemKey, Long>()
        val held = runCatching { equipment.itemInMainHand }.getOrNull()
        if (held != null && !held.type.isAir) held.addTo(totals)
        return totals
    }

    private companion object {
        const val VISIT_MS = 20_000L
    }
}
