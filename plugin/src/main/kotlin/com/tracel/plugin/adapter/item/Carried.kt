package com.tracel.plugin.adapter.item

import com.tracel.annotations.Unstable
import com.tracel.model.item.ItemKey
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.CrossbowMeta

@Unstable
private const val MAX_NESTING = 8

/**
 * Whether this stack can hold other items inside it: a bundle, a crossbow.
 *
 * What it holds is the ledger's too, loose in the same holder. Hashed into the key instead, filling a
 * bundle read as diamonds burned and a new bundle minted, and a rollback of the theft left the thief
 * the bundle with every diamond still in it.
 */
fun ItemStack.canCarry(): Boolean = type == Material.CROSSBOW || type.name.endsWith("BUNDLE")

/** What this stack holds inside it, one level deep. Empty for anything that holds nothing. */
fun ItemStack.carried(): List<ItemStack> {
    if (!canCarry() || !hasItemMeta()) return emptyList()
    return when (val meta = itemMeta) {
        is BundleMeta -> if (meta.hasItems()) meta.items.filter { !it.isEmpty && !it.type.isAir } else emptyList()
        is CrossbowMeta -> if (meta.hasChargedProjectiles()) meta.chargedProjectiles.filter { !it.isEmpty && !it.type.isAir } else emptyList()
        else -> emptyList()
    }
}

/** This stack with [inside] as what it holds; an empty list empties it. */
fun ItemStack.withCarried(inside: List<ItemStack>): ItemStack {
    val meta = itemMeta ?: return this
    when (meta) {
        is BundleMeta -> meta.setItems(inside.ifEmpty { null })
        is CrossbowMeta -> meta.setChargedProjectiles(inside.ifEmpty { null })
        else -> return this
    }
    itemMeta = meta
    return this
}

/** Adds this stack and everything it carries, all the way down, to [totals]. */
fun ItemStack.addTo(totals: MutableMap<ItemKey, Long>, times: Long = 1L, depth: Int = 0) {
    if (isEmpty || type.isAir || amount <= 0) return
    totals.merge(toItemKey(), amount * times, Long::plus)
    if (depth >= MAX_NESTING) return
    for (inside in carried()) inside.addTo(totals, amount * times, depth + 1)
}

/** [quantity] of this stack as ledger totals: its key, plus what each one carries. */
fun ItemStack.totalsOf(quantity: Long): Map<ItemKey, Long> {
    if (!canCarry()) return mapOf(toItemKey() to quantity)
    val totals = LinkedHashMap<ItemKey, Long>()
    totals[toItemKey()] = quantity
    for (inside in carried()) inside.addTo(totals, quantity)
    return totals
}
