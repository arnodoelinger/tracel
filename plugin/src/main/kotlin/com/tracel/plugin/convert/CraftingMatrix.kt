package com.tracel.plugin.convert

import com.tracel.model.item.ItemKey
import org.bukkit.inventory.ItemStack

/**
 * Reads `Inventory.contents`, which for a [org.bukkit.inventory.CraftingInventory] includes
 * the result slot too.
 */
fun Array<ItemStack?>.toItemTotals(): Map<ItemKey, Long> {
    val totals = mutableMapOf<ItemKey, Long>()
    for (stack in this) {
        if (stack == null || stack.type.isAir) continue
        val key = stack.toItemKey()
        totals[key] = (totals[key] ?: 0L) + stack.amount
    }
    return totals
}

/** Everything present in `this` but not in [other], i.e. what was lost going from `this` to [other]. */
fun Map<ItemKey, Long>.lostRelativeTo(other: Map<ItemKey, Long>): Map<ItemKey, Long> =
    keys.mapNotNull { key ->
        val lost = (this[key] ?: 0L) - (other[key] ?: 0L)
        if (lost > 0) key to lost else null
    }.toMap()
