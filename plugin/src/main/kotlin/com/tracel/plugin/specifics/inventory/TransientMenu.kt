package com.tracel.plugin.specifics.inventory

import org.bukkit.event.inventory.InventoryType

/** Menus nobody owns but the player using them: what sits in their inputs goes back to the player on close. */
internal enum class TransientMenu(val type: InventoryType, val inputs: IntRange) {
    ANVIL(InventoryType.ANVIL, 0..1),
    GRINDSTONE(InventoryType.GRINDSTONE, 0..1),
    STONECUTTER(InventoryType.STONECUTTER, 0..0),
    SMITHING(InventoryType.SMITHING, 0..2),
    LOOM(InventoryType.LOOM, 0..2),
    CARTOGRAPHY(InventoryType.CARTOGRAPHY, 0..1),
    ENCHANTING(InventoryType.ENCHANTING, 0..1),
    BEACON(InventoryType.BEACON, 0..0),
    MERCHANT(InventoryType.MERCHANT, 0..1);

    companion object {
        private val inputsByType: Map<InventoryType, IntRange> = entries.associate { it.type to it.inputs }

        fun inputsOf(type: InventoryType): IntRange? = inputsByType[type]
    }
}
