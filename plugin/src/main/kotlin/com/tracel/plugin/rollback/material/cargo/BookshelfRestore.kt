package com.tracel.plugin.rollback.material.cargo

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.matches
import com.tracel.plugin.rollback.material.item.stackFor
import com.tracel.plugin.rollback.material.item.stacksOf
import org.bukkit.block.ChiseledBookshelf
import org.bukkit.block.data.type.ChiseledBookshelf as ChiseledBookshelfData
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Applies [deltas] on bookshelf books. */
internal fun MaterialRestorer.applyBookshelf(
    shelf: ChiseledBookshelf,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    moves: Moves,
    preferredSlots: List<ContainerSlotEntry> = emptyList(),
) {
    val block = shelf.block
    val snap = block.getState(true) as? ChiseledBookshelf ?: shelf
    val inv = snap.snapshotInventory
    for ((itemKey, delta) in deltas) {
        val template = stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        if (delta > 0) {
            val wanted = preferredSlots.filter { it.itemKey == itemKey }
            for (over in intoBookshelfSlots(inv, itemKey, delta, template, wanted)) {
                moves.overflow += itemKey to over
            }
        } else {
            moves.short(itemKey, takeBooks(inv, itemKey, -delta))
        }
    }
    runCatching { snap.lastInteractedSlot = -1 }
    val data = snap.blockData as? ChiseledBookshelfData
    if (data != null) {
        for (slot in 0 until data.maximumOccupiedSlots) {
            val held = inv.getItem(slot)
            data.setSlotOccupied(slot, held != null && !held.isEmpty && !held.type.isAir)
        }
        snap.blockData = data
    }
    if (snap.isPlaced) runCatching { snap.update(true, false) }
}

private fun intoBookshelfSlots(
    inventory: Inventory,
    itemKey: ItemKey,
    amount: Long,
    template: ItemStack,
    preferredSlots: List<ContainerSlotEntry> = emptyList(),
): List<ItemStack> {
    var remaining = amount
    fun place(slot: Int): Boolean {
        if (slot !in 0 until inventory.size) return false
        val held = inventory.getItem(slot)
        if (held != null && !held.isEmpty && !held.type.isAir) return false
        inventory.setItem(slot, template.clone().apply { this.amount = 1 })
        remaining -= 1L
        return true
    }
    for (entry in preferredSlots) {
        if (remaining <= 0L) break
        place(entry.slot)
    }
    for (slot in 0 until inventory.size) {
        if (remaining <= 0L) break
        place(slot)
    }
    if (remaining <= 0L) return emptyList()
    return stacksOf(itemKey, remaining, template)
}

private fun takeBooks(
    inventory: Inventory,
    itemKey: ItemKey,
    amount: Long,
): Long {
    var remaining = amount
    for (slot in 0 until inventory.size) {
        if (remaining <= 0L) break
        val held = inventory.getItem(slot) ?: continue
        if (held.isEmpty || held.type.isAir) continue
        if (!held.matches(itemKey)) continue
        inventory.setItem(slot, ItemStack.empty())
        remaining -= 1L
    }
    return remaining
}
