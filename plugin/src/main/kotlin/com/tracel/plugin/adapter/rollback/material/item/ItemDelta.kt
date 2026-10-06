package com.tracel.plugin.adapter.rollback.material.item

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.PendingItemForms
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.util.log.Warnings
import java.util.concurrent.ConcurrentHashMap
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
    worn: WornStacks? = null,
) {
    val template = stackFor(itemKey, 1, form)
    if (template == null) {
        moves.problem("unknown material ${itemKey.material}")
        return
    }
    val carried = worn?.takeIf { WornStacks.wears(itemKey) }
    if (delta > 0) {
        var left = delta
        while (carried != null && left > 0L) {
            val real = carried.next(itemKey) ?: break
            val give = minOf(left, real.amount.toLong())
            for (over in giveInto(inventory, itemKey, give, real, preferredSlots)) moves.overflow += itemKey to over
            left -= give
        }
        for (over in giveInto(inventory, itemKey, left, template, preferredSlots)) moves.overflow += itemKey to over
    } else {
        moves.short(itemKey, takeByKey(inventory, itemKey, -delta, carried) { left ->
            for (over in inventory.addItem(left).values) moves.overflow += over.toItemKey() to over
        })
    }
}

private val rebuiltForms = ConcurrentHashMap<ByteArray, ItemStack>()
private const val MAX_REBUILT_FORMS = 4_096

/** [amount] of [itemKey], rebuilt from [form] if there is one and plain if there is not. */
internal fun MaterialRestorer.stackFor(itemKey: ItemKey, amount: Int, form: ByteArray?): ItemStack? {
    if (form != null) {
        val rebuilt =
            rebuiltForms[form]?.clone() ?: runCatching { ItemStack.deserializeBytes(form) }.getOrNull()?.also {
                if (rebuiltForms.size >= MAX_REBUILT_FORMS) rebuiltForms.clear()
                rebuiltForms[form] = it.clone()
            }
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
