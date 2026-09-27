package com.tracel.plugin.rollback.material.cargo

import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.*
import org.bukkit.entity.ItemFrame

/** Applies [deltas] to a frame's single held item. */
internal fun MaterialRestorer.applyItemFrame(
    frame: ItemFrame,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    moves: Moves,
    worn: WornStacks? = null,
) {
    for ((itemKey, delta) in deltas) {
        val real = if (delta > 0 && WornStacks.wears(itemKey)) worn?.next(itemKey) else null
        val template = real?.clone()?.apply { amount = 1 } ?: stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        val current = frame.item
        val held = if (current.isEmpty || current.type.isAir) null else current
        if (delta > 0) {
            when {
                // Occupied, even by the same key: the ledger already credited us, so spill
                held != null -> for (over in stacksOf(itemKey, delta, template)) moves.overflow += itemKey to over
                else -> {
                    // Two-arg setItem: one-arg plays the placement sound
                    val rotation = frame.rotation
                    frame.setItem(template, false)
                    frame.rotation = rotation
                    // One item per frame; extras spill
                    if (delta > 1) {
                        for (over in stacksOf(itemKey, delta - 1, template)) moves.overflow += itemKey to over
                    }
                }
            }
        } else {
            when {
                held == null -> moves.short(itemKey, -delta)
                held.matches(itemKey) -> {
                    // Two-arg: one-arg removal has been seen to leave a copy on the ground
                    frame.setItem(null, false)
                    if (-delta > 1) moves.short(itemKey, -delta - 1)
                }
                else -> moves.short(itemKey, -delta)
            }
        }
    }
}
