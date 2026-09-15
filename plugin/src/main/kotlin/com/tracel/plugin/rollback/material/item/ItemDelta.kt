package com.tracel.plugin.rollback.material.item

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.PendingItemForms
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.util.Warnings
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Apply one key. Overflow as stacks, shortfall named. */
internal fun MaterialRestorer.applyDelta(
    itemKey: ItemKey,
    delta: Long,
    form: ByteArray?,
    moves: Moves,
    inventory: Inventory,
    preferredSlots: List<ContainerSlotEntry> = emptyList(),
) {
    val template = stackFor(itemKey, 1, form)
    if (template == null) {
        moves.problem("unknown material ${itemKey.material}")
        return
    }
    if (delta > 0) {
        for (over in giveInto(inventory, itemKey, delta, template, preferredSlots)) moves.overflow += itemKey to over
    } else {
        moves.short(itemKey, takeByKey(inventory, itemKey, -delta))
    }
}

/** [amount] of [itemKey], rebuilt from [form] if there is one and plain if there is not. */
internal fun MaterialRestorer.stackFor(itemKey: ItemKey, amount: Int, form: ByteArray?): ItemStack? {
    if (form != null) {
        val rebuilt = runCatching { ItemStack.deserializeBytes(form) }.getOrNull()
        if (rebuilt != null) return rebuilt.apply { this.amount = amount }
        Warnings.once(logger, "form:$itemKey") {
            "stored form of $itemKey no longer deserializes on this server — restoring it plain"
        }
    }
    if (itemKey.decoration != null && form == null) {
        logger.fine("no stored form for decorated item $itemKey — restoring a plain ${itemKey.material} stack")
    }
    // Bad keys (non-item materials) must not throw the whole rollback
    val material = runCatching { Material.valueOf(itemKey.material) }.getOrNull()?.takeIf { it.isItem } ?: return null
    return ItemStack(material, amount)
}

/** Decorated forms once, off region threads. */
internal suspend fun MaterialRestorer.formsFor(deltas: Map<HolderId, Map<ItemKey, Long>>): Map<ItemKey, ByteArray> {
    val decorated = deltas.values.flatMap { it.keys }.filter { it.decoration != null }.distinct()
    if (decorated.isEmpty()) return emptyMap()
    val stored = services.atomically {
        decorated.mapNotNull { key -> key.decoration?.let { services.itemForms.find(it) }?.let { key to it } }.toMap()
    }
    if (stored.size == decorated.size) return stored
    val missing = decorated.filter { it !in stored }
    val fromQueue = missing.mapNotNull { key ->
        key.decoration?.let { PendingItemForms.find(it) }?.let { key to it }
    }
    return stored + fromQueue
}
