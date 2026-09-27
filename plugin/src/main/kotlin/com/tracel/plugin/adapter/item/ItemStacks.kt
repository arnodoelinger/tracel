package com.tracel.plugin.adapter.item

import com.tracel.model.item.ItemKey
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Splits a raw ledger quantity into vanilla-legal stacks, never exceeding
 * the material's own max stack size.
 */
fun ItemKey.toItemStacks(quantity: Long): List<ItemStack> {
    val mat = runCatching { Material.valueOf(material) }.getOrNull() ?: return emptyList()
    val maxStack = mat.maxStackSize.coerceAtLeast(1).toLong()
    var remaining = quantity
    val stacks = mutableListOf<ItemStack>()
    while (remaining > 0) {
        val amount = minOf(remaining, maxStack)
        stacks += ItemStack(mat, amount.toInt())
        remaining -= amount
    }
    return stacks
}
