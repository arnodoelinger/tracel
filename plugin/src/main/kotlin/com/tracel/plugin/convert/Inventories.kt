package com.tracel.plugin.convert

import com.tracel.model.item.ItemKey
import org.bukkit.entity.HumanEntity
import org.bukkit.inventory.Inventory

/**
 * Aggregates by item key, not by slot — matches the ledger's own granularity. Rearranging
 * items within one inventory (sorting a chest, moving a stack between hotbar slots) changes
 * nothing here at all, which is exactly the point: [com.tracel.engine.capture.SnapshotDiffer]
 * only ever sees a real gain or loss, never a slot shuffle dressed up as one.
 */
fun Inventory.toItemTotals(): Map<ItemKey, Long> {
    val totals = mutableMapOf<ItemKey, Long>()
    for (stack in contents) {
        if (stack == null || stack.type.isAir) continue
        val key = stack.toItemKey()
        totals[key] = (totals[key] ?: 0L) + stack.amount
    }
    return totals
}

/**
 * Folds [player]'s cursor stack into these totals — the item a player is physically holding
 * mid-click lives outside any [Inventory] `Bukkit` exposes. Left out, picking something up and
 * putting it down again reads as an unmatched loss and an unmatched gain instead of nothing
 * having moved at all.
 */
fun Map<ItemKey, Long>.withCursor(player: HumanEntity): Map<ItemKey, Long> {
    val cursor = player.itemOnCursor
    if (cursor.type.isAir) return this
    val key = cursor.toItemKey()
    return this + (key to (this[key] ?: 0L) + cursor.amount)
}
