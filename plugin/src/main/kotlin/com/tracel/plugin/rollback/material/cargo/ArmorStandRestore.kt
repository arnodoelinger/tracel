package com.tracel.plugin.rollback.material.cargo

import com.tracel.annotations.Unstable
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.matches
import com.tracel.plugin.rollback.material.item.stackFor
import com.tracel.plugin.rollback.material.item.stacksOf
import org.bukkit.entity.ArmorStand
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * Applies [deltas] to a stand's worn equipment: gives go to the natural slot, then hands;
 * takes match the exact key.
 */
@Unstable
internal fun MaterialRestorer.applyArmorStand(
    stand: ArmorStand,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    moves: Moves,
) {
    val eq = stand.equipment
    for ((itemKey, delta) in deltas) {
        val template = stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        if (delta > 0) {
            var remaining = delta
            for (slot in slotsFor(template)) {
                if (remaining <= 0L) break
                val worn = eq.getItem(slot)
                if (!worn.isEmpty && !worn.type.isAir) {
                    // Snapshot already wearing this key; skip or we mint a second copy
                    if (worn.matches(itemKey)) remaining -= worn.amount.toLong().coerceAtMost(remaining)
                    continue
                }
                val take = minOf(remaining, template.maxStackSize.toLong().coerceAtLeast(1L))
                eq.setItem(slot, template.clone().apply { amount = take.toInt() })
                remaining -= take
            }
            if (remaining > 0L) {
                for (over in stacksOf(itemKey, remaining, template)) moves.overflow += itemKey to over
            }
        } else {
            var remaining = -delta
            for (slot in EquipmentSlot.entries) {
                if (remaining <= 0L) break
                val worn = runCatching { eq.getItem(slot) }.getOrNull() ?: continue
                if (worn.isEmpty || worn.type.isAir || !worn.matches(itemKey)) continue
                val take = minOf(remaining, worn.amount.toLong()).toInt()
                remaining -= take
                if (take >= worn.amount) eq.setItem(slot, ItemStack.empty())
                else eq.setItem(slot, worn.clone().apply { amount -= take })
            }
            moves.short(itemKey, remaining)
        }
    }
}

/** Where a stand would wear this, then the places it can still hold it. */
internal fun MaterialRestorer.slotsFor(stack: ItemStack): List<EquipmentSlot> {
    val natural = armorSlot(stack)
    val hands = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)
    return listOf(natural) + hands.filter { it != natural }
}

/** Which equipment slot a stand naturally wears [stack] in, going by its material name. */
@Unstable
internal fun MaterialRestorer.armorSlot(stack: ItemStack): EquipmentSlot {
    val name = stack.type.name
    return when {
        name.endsWith("_HELMET") || name == "TURTLE_HELMET" || name == "CARVED_PUMPKIN" -> EquipmentSlot.HEAD
        name.endsWith("_CHESTPLATE") || name == "ELYTRA" -> EquipmentSlot.CHEST
        name.endsWith("_LEGGINGS") -> EquipmentSlot.LEGS
        name.endsWith("_BOOTS") -> EquipmentSlot.FEET
        else -> EquipmentSlot.HAND
    }
}
