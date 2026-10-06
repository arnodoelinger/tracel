package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.rollback.material.item.matches
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot

/**
 * Armor and a shield given back go on the body when that slot is free: a looted corpse got its helmet
 * back in the hotbar. Moved from where the give put it, so the worn stack keeps its wear.
 */
internal fun wearGiven(player: Player, deltas: Map<ItemKey, Long>) {
    val inventory = player.inventory
    for ((itemKey, delta) in deltas) {
        if (delta <= 0L) continue
        val slot = runCatching { Material.valueOf(itemKey.material).equipmentSlot }.getOrNull() ?: continue
        if (slot == EquipmentSlot.HAND || slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue
        val worn = inventory.getItem(slot)
        if (!worn.isEmpty && !worn.type.isAir) continue
        for (index in 0 until STORAGE_SLOTS) {
            val stack = inventory.getItem(index) ?: continue
            if (stack.isEmpty || stack.maxStackSize != 1 || !stack.matches(itemKey)) continue
            inventory.setItem(slot, stack.clone().apply { amount = 1 })
            inventory.setItem(index, if (stack.amount > 1) stack.clone().apply { amount -= 1 } else null)
            break
        }
    }
}
