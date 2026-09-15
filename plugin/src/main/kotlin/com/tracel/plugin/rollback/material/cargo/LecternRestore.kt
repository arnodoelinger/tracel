package com.tracel.plugin.rollback.material.cargo

import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.matches
import com.tracel.plugin.rollback.material.item.stackFor
import com.tracel.plugin.rollback.material.item.stacksOf
import org.bukkit.block.Lectern

/** Applies [deltas] to a lectern's single book slot directly. */
internal fun MaterialRestorer.applyLectern(
    lectern: Lectern,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    moves: Moves,
) {
    val inventory = lectern.inventory
    for ((itemKey, delta) in deltas) {
        val template = stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        val current = inventory.getItem(0)
        val held = if (current == null || current.isEmpty || current.type.isAir) null else current
        if (delta > 0) {
            when {
                held != null && held.matches(itemKey) -> Unit
                held != null -> for (over in stacksOf(itemKey, delta, template)) moves.overflow += itemKey to over
                else -> {
                    inventory.setItem(0, template)
                    if (delta > 1) {
                        for (over in stacksOf(itemKey, delta - 1, template)) moves.overflow += itemKey to over
                    }
                }
            }
        } else {
            when {
                held == null -> moves.short(itemKey, -delta)
                held.matches(itemKey) -> {
                    inventory.setItem(0, null)
                    if (-delta > 1) moves.short(itemKey, -delta - 1)
                }
                else -> moves.short(itemKey, -delta)
            }
        }
    }
}
