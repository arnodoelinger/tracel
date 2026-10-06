package com.tracel.plugin.specifics.item

import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot

/** Whether this is a saddle. */
internal fun Material.isSaddle(): Boolean = name == "SADDLE"

/** Whether a horse wears this as armour. */
internal fun Material.isHorseArmor(): Boolean =
    name.endsWith("_HORSE_ARMOR") || name == "HORSE_ARMOR" || runCatching { equipmentSlot }.getOrNull() == EquipmentSlot.BODY

/** Whether a llama wears this as decoration. */
internal fun Material.isLlamaDecor(): Boolean = name.endsWith("_CARPET")
