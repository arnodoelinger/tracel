package com.tracel.plugin.rollback.material.cargo

import com.tracel.annotations.Unstable
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.WornStacks
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
    worn: WornStacks? = null,
) {
    val eq = stand.equipment
    for ((itemKey, delta) in deltas) {
        val real = if (delta > 0 && WornStacks.wears(itemKey)) worn?.next(itemKey) else null
        val template = real?.clone()?.apply { amount = 1 } ?: stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        if (delta > 0) {
            var remaining = delta
            for (slot in slotsFor(template)) {
                if (remaining <= 0L) break
                val worn = eq.getItem(slot)
                if (!worn.isEmpty && !worn.type.isAir) continue
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

/**
 * Which equipment slot a stand naturally wears [stack] in, as the server itself says: guessed from the
 * name, a player head or a skull came back in the stand's hand.
 */
internal fun MaterialRestorer.armorSlot(stack: ItemStack): EquipmentSlot =
    when (val slot = runCatching { stack.type.equipmentSlot }.getOrNull()) {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFF_HAND -> slot
        else -> EquipmentSlot.HAND
    }
