package com.tracel.plugin.adapter.rollback.material.cargo

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.adapter.rollback.material.item.matches
import com.tracel.plugin.adapter.rollback.material.item.stackFor
import com.tracel.plugin.adapter.rollback.material.item.stacksOf
import com.tracel.plugin.rollback.material.MaterialRestorer
import org.bukkit.block.Campfire
import org.bukkit.inventory.ItemStack

/** Applies [deltas] to a campfire's four food slots, which live on the block state. */
internal fun MaterialRestorer.applyCampfire(
    campfire: Campfire,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    moves: Moves,
) {
    for ((itemKey, delta) in deltas) {
        val template = stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        if (delta > 0) {
            var remaining = delta
            for (slot in 0 until campfire.size) {
                if (remaining <= 0L) break
                val held = campfire.getItem(slot)
                // Cooking already is not delivered
                if (held != null && !held.isEmpty && !held.type.isAir) continue
                // One item per slot
                campfire.setItem(slot, template.clone().apply { amount = 1 })
                remaining--
            }
            if (remaining > 0L) {
                for (over in stacksOf(itemKey, remaining, template)) moves.overflow += itemKey to over
            }
        } else {
            var remaining = -delta
            for (slot in 0 until campfire.size) {
                if (remaining <= 0L) break
                val held = campfire.getItem(slot) ?: continue
                if (held.isEmpty || held.type.isAir || !held.matches(itemKey)) continue
                val take = minOf(remaining, held.amount.toLong()).toInt()
                remaining -= take
                if (take >= held.amount) campfire.setItem(slot, ItemStack.empty())
                else campfire.setItem(slot, held.clone().apply { amount -= take })
            }
            moves.short(itemKey, remaining)
        }
    }
}
