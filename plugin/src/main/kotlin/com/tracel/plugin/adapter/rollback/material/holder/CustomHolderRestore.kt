package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.adapter.rollback.material.item.stackFor
import com.tracel.plugin.adapter.rollback.material.spill.spillInRegion
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.spill.Spill
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.inventory.ItemStack

/**
 * Apply to a container an addon keeps, through nothing but its slots: read them, change copies, write back the
 * slots that changed.
 *
 * The preflight already refused a rollback whose addon is gone; one that left since fails here.
 */
internal suspend fun MaterialRestorer.applyToCustom(
    holder: HolderId.Custom,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
): ApplyResult {
    val bound = Extensions.live.holder(holder)
        ?: return ApplyResult.Failed("${holder.kind} ${holder.id} is not known any more: its addon is gone.")
    val place = bound.place
    val thread = place?.let { services.schedulers.region(HolderId.Block(ApiConversions.pos(it))) }
        ?: services.schedulers.global
    return withContext(thread) {
        val before = bound.read() ?: return@withContext ApplyResult.Failed("${holder.kind} ${holder.id} is gone.")
        val slots = before.map { it?.clone() }.toMutableList()
        val moves = Moves()
        for ((itemKey, delta) in deltas) {
            val template = stackFor(itemKey, 1, forms[itemKey])
            if (template == null) {
                moves.problem("unknown material ${itemKey.material}.")
                continue
            }
            if (delta > 0L) {
                val left = give(slots, itemKey, delta, template)
                if (left > 0L) overflow(moves, itemKey, left, template)
            } else {
                moves.short(itemKey, take(slots, itemKey, -delta))
            }
        }
        for (slot in slots.indices) if (!same(before[slot], slots[slot])) bound.write(slot, slots[slot])

        val world = place?.let { Bukkit.getWorld(it.world) }
        if (place != null && world != null) {
            spillInRegion(holder, moves, world, Location(world, place.x + 0.5, place.y + 0.5, place.z + 0.5), sink)
        } else if (moves.overflow.isNotEmpty()) {
            val lost = moves.overflow.joinToString { (key, stack) -> "${key.material} x${stack.amount}" }
            moves.overflow.clear()
            moves.problem("no room for $lost, and it has no place to drop them.")
        }
        services.differ.rebaseline(holder, (bound.read() ?: slots).toItemTotals())
        moves.failure() ?: ApplyResult.Ok
    }
}

private fun give(slots: MutableList<ItemStack?>, itemKey: ItemKey, amount: Long, template: ItemStack): Long {
    var left = amount
    val max = template.maxStackSize.coerceAtLeast(1)
    for (slot in slots.indices) {
        if (left == 0L) return 0L
        val stack = slots[slot] ?: continue
        if (stack.isEmpty || stack.toItemKey() != itemKey || stack.amount >= max) continue
        val put = minOf(left, (max - stack.amount).toLong()).toInt()
        stack.amount += put
        left -= put
    }
    for (slot in slots.indices) {
        if (left == 0L) return 0L
        if (slots[slot]?.isEmpty == false) continue
        val put = minOf(left, max.toLong()).toInt()
        slots[slot] = template.clone().apply { this.amount = put }
        left -= put
    }
    return left
}

private fun take(slots: MutableList<ItemStack?>, itemKey: ItemKey, amount: Long): Long {
    var left = amount
    for (slot in slots.indices.reversed()) {
        if (left == 0L) return 0L
        val stack = slots[slot] ?: continue
        if (stack.isEmpty || stack.toItemKey() != itemKey) continue
        val took = minOf(left, stack.amount.toLong()).toInt()
        slots[slot] = if (took == stack.amount) null else stack.apply { this.amount -= took }
        left -= took
    }
    return left
}

private fun overflow(moves: Moves, itemKey: ItemKey, amount: Long, template: ItemStack) {
    var left = amount
    val max = template.maxStackSize.coerceAtLeast(1)
    while (left > 0L) {
        val put = minOf(left, max.toLong()).toInt()
        moves.overflow += itemKey to template.clone().apply { this.amount = put }
        left -= put
    }
}

private fun same(a: ItemStack?, b: ItemStack?): Boolean {
    val left = a?.takeUnless { it.isEmpty }
    val right = b?.takeUnless { it.isEmpty }
    return left == right
}
