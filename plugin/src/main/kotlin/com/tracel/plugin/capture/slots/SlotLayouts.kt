package com.tracel.plugin.capture.slots

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.capture.commit.CommitQueue
import com.tracel.plugin.services.TracelServices
import org.bukkit.inventory.Inventory
import java.util.concurrent.ConcurrentHashMap

/** Which slot a container's stacks sit in, written down whenever it changes. */
internal class SlotLayouts(private val services: TracelServices, private val commits: CommitQueue) {
    private val lastSeenSlots = ConcurrentHashMap<HolderId, List<ContainerSlotEntry>>()

    /**
     * Captures the layout of occupied slots in a cargo container and records it for
     * the given holder.
     */
    @Suppress("ReplaceManualRangeWithIndicesCalls")
    fun capture(holder: HolderId, cargo: CargoSlots) {
        val slots = ArrayList<ContainerSlotEntry>(cargo.size)
        for (slot in 0 until cargo.size) {
            val stack = cargo.get(slot) ?: continue
            if (stack.isEmpty || stack.type.isAir) continue
            slots += ContainerSlotEntry(slot, stack.toItemKey(), stack.amount.toLong())
        }
        rememberSlots(holder, slots)
    }

    /** The same for a plain [inventory]. */
    fun capture(holder: HolderId, inventory: Inventory) {
        val contents = inventory.contents
        val slots = ArrayList<ContainerSlotEntry>(contents.size)
        for (slot in contents.indices) {
            val stack = contents[slot] ?: continue
            if (stack.isEmpty || stack.type.isAir) continue
            slots += ContainerSlotEntry(slot, stack.toItemKey(), stack.amount.toLong())
        }
        rememberSlots(holder, slots)
    }

    private fun rememberSlots(holder: HolderId, slots: List<ContainerSlotEntry>) {
        if (lastSeenSlots.put(holder, slots) == slots) return
        val epochMillis = System.currentTimeMillis()
        commits.committing("container slots at $holder") { services.containerSlots.record(holder, epochMillis, slots) }
    }
}
