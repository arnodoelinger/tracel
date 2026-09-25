package com.tracel.plugin.adapter.item

import com.tracel.model.item.ItemKey
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.ItemStack
import kotlin.collections.iterator

/** Reads `Inventory.contents`, including the result slot of a [CraftingInventory]. */
fun Array<ItemStack?>.toItemTotals(): Map<ItemKey, Long> = asIterable().toItemTotals()

/** Iterable of [ItemStack]s, including the result slot of a [CraftingInventory]. */
fun Iterable<ItemStack?>.toItemTotals(): Map<ItemKey, Long> {
    val totals = mutableMapOf<ItemKey, Long>()
    for (stack in this) {
        if (stack == null || stack.type.isAir || stack.amount <= 0) continue
        stack.addTo(totals)
    }
    return totals
}

/** Combines item quantities from two views of the same holder. */
fun Map<ItemKey, Long>.mergedWith(other: Map<ItemKey, Long>): Map<ItemKey, Long> {
    // Not "+" here because on a map that replaces the left side's count instead of
    // adding to it, which turns a player holding, for example, four planks in the
    // grid and four in a pocket into a player holding four.
    if (other.isEmpty()) return this
    if (isEmpty()) return other
    val totals = toMutableMap()
    for ((key, quantity) in other) totals[key] = (totals[key] ?: 0L) + quantity
    return totals
}

/** @return the items lost when changing from [this] to [other]. */
fun Map<ItemKey, Long>.lostRelativeTo(other: Map<ItemKey, Long>): Map<ItemKey, Long> =
    keys.mapNotNull { key ->
        val lost = (this[key] ?: 0L) - (other[key] ?: 0L)
        if (lost > 0) key to lost else null
    }.toMap()
