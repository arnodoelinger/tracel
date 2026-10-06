package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.adapter.rollback.material.item.matches
import com.tracel.plugin.adapter.rollback.material.item.stackFor
import com.tracel.plugin.adapter.rollback.material.item.stacksOf
import com.tracel.plugin.rollback.material.MaterialRestorer
import org.bukkit.entity.Mob
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

internal fun MaterialRestorer.applyMobEquipment(
    mob: Mob,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    moves: Moves
) {
    val equipment = mob.equipment
    for ((itemKey, delta) in deltas.entries.sortedBy { it.value > 0L }) {
        if (delta < 0L) {
            var left = -delta
            for (slot in EquipmentSlot.entries) {
                if (left <= 0L) break
                val held = runCatching { equipment.getItem(slot) }.getOrNull() ?: continue
                if (held.isEmpty || !held.matches(itemKey)) continue
                val take = minOf(left, held.amount.toLong())
                equipment.setItem(
                    slot,
                    if (take >= held.amount) ItemStack.empty() else held.clone().apply { amount -= take.toInt() })
                left -= take
            }
            moves.short(itemKey, left)
            continue
        }
        val template = stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        var left = delta
        val natural = runCatching { template.type.equipmentSlot }.getOrNull()
        for (slot in listOfNotNull(natural, EquipmentSlot.HAND).distinct()) {
            if (left <= 0L) break
            if (!runCatching { mob.canUseEquipmentSlot(slot) }.getOrDefault(false)) continue
            val held = runCatching { equipment.getItem(slot) }.getOrNull()
            if (held != null && !held.isEmpty) continue
            val give = if (slot == EquipmentSlot.HAND) minOf(left, template.maxStackSize.toLong()) else 1L
            equipment.setItem(slot, template.clone().apply { amount = give.toInt() })
            runCatching { equipment.setDropChance(slot, 1f) }
            left -= give
        }
        for (over in stacksOf(itemKey, left, template)) moves.overflow += itemKey to over
    }
}
