package com.tracel.plugin.adapter.rollback.material.item

import com.tracel.plugin.specifics.item.isHorseArmor
import com.tracel.plugin.specifics.item.isLlamaDecor
import com.tracel.plugin.specifics.item.isSaddle
import org.bukkit.inventory.ArmoredHorseInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.LlamaInventory
import org.bukkit.inventory.SaddledMountInventory

internal fun intoTack(inventory: SaddledMountInventory, stack: ItemStack): ItemStack? {
    val worn: ItemStack?
    val wear: (ItemStack) -> Unit
    val type = stack.type
    when {
        type.isSaddle() -> {
            worn = runCatching { inventory.saddle }.getOrNull()
            wear = { runCatching { inventory.saddle = it } }
        }

        inventory is ArmoredHorseInventory && type.isHorseArmor() -> {
            worn = runCatching { inventory.armor }.getOrNull()
            wear = { runCatching { inventory.armor = it } }
        }

        inventory is LlamaInventory && type.isLlamaDecor() -> {
            worn = runCatching { inventory.decor }.getOrNull()
            wear = { runCatching { inventory.decor = it } }
        }
        // Freight
        else -> return stack
    }
    // Already wearing one, spill the extra and don't conjure a second saddle
    if (worn != null && !worn.isEmpty && !worn.type.isAir) return stack
    wear(stack.clone().apply { amount = 1 })
    return stack.takeIf { it.amount > 1 }?.clone()?.apply { amount = stack.amount - 1 }
}
