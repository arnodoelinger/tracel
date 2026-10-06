package com.tracel.plugin.adapter.rollback.material.item

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.canCarry
import com.tracel.plugin.adapter.item.carried
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.withCarried
import org.bukkit.inventory.*

/**
 * Takes [amount] of [itemKey] out of [inventory], matched by key.
 *
 * Empty bundles go before full ones, and a full one taken leaves what it held behind in [loose]: the
 * ledger books that apart from the bundle. Short of loose stacks, it takes from inside bundles.
 */
internal fun takeByKey(
    inventory: Inventory,
    itemKey: ItemKey,
    amount: Long,
    worn: WornStacks? = null,
    loose: (ItemStack) -> Unit = {},
): Long {
    if (amount <= 0L) return 0L
    var remaining = amount
    val contents = inventory.contents
    val order =
        contents.indices.sortedBy { slot -> contents[slot]?.let { it.canCarry() && it.carried().isNotEmpty() } == true }
    for (slot in order) {
        if (remaining <= 0L) break
        val stack = contents[slot] ?: continue
        if (stack.isEmpty || stack.type.isAir) continue
        if (!stack.matches(itemKey)) continue
        val take = minOf(remaining, stack.amount.toLong()).toInt()
        worn?.took(itemKey, stack.clone().apply { this.amount = take })
        remaining -= take
        val inside = stack.carried()
        if (take >= stack.amount) {
            inventory.setItem(slot, null)
        } else {
            stack.amount -= take
            inventory.setItem(slot, stack)
        }
        for (item in inside) item.clone().apply { this.amount *= take }.let(loose)
    }
    if (remaining > 0L) remaining = takeCarried(inventory, itemKey, remaining)
    return remaining
}

private fun takeCarried(inventory: Inventory, itemKey: ItemKey, amount: Long): Long {
    var remaining = amount
    val contents = inventory.contents
    for (slot in contents.indices) {
        if (remaining <= 0L) break
        val carrier = contents[slot] ?: continue
        if (!carrier.canCarry() || carrier.amount != 1) continue
        val inside = carrier.carried()
        if (inside.isEmpty()) continue
        var changed = false
        val kept = ArrayList<ItemStack>(inside.size)
        for (item in inside) {
            if (remaining > 0L && item.matches(itemKey)) {
                val take = minOf(remaining, item.amount.toLong()).toInt()
                remaining -= take
                changed = true
                if (take < item.amount) kept += item.clone().apply { this.amount -= take }
            } else {
                kept += item
            }
        }
        if (changed) inventory.setItem(slot, carrier.clone().withCarried(kept))
    }
    return remaining
}

/** Whether this stack is the material the ledger means by [itemKey]. */
internal fun ItemStack.matches(itemKey: ItemKey): Boolean {
    if (type.name != itemKey.material) return false
    return toItemKey() == itemKey
}
