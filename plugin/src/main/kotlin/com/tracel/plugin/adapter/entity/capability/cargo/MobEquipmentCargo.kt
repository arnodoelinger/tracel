package com.tracel.plugin.adapter.entity.capability.cargo

import org.bukkit.entity.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/** What a mob picked up or was handed. */
internal object MobEquipmentCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is Mob && entity !is ArmorStand && entity !is AbstractHorse

    fun owned(mob: Mob): List<EquipmentSlot> {
        val equipment = mob.equipment
        return EquipmentSlot.entries.filter { slot ->
            runCatching {
                mob.canUseEquipmentSlot(slot) &&
                        (equipment.getDropChance(slot) >= 1f || mob is CopperGolem && slot == EquipmentSlot.HAND)
            }.getOrDefault(false)
        }
    }

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        val mob = entity as Mob
        for (slot in owned(mob)) addIfPresent(runCatching { mob.equipment.getItem(slot) }.getOrNull(), add)
    }

    override fun empty(entity: Entity) {
        val mob = entity as Mob
        for (slot in owned(mob)) runCatching { mob.equipment.setItem(slot, ItemStack.empty()) }
    }

    override fun save(entity: Entity): Any {
        val mob = entity as Mob
        return owned(mob).associateWith { mob.equipment.getItem(it).clone() }
    }

    override fun restore(entity: Entity, saved: Any?) {
        @Suppress("UNCHECKED_CAST")
        val slots = saved as? Map<EquipmentSlot, ItemStack> ?: return
        val mob = entity as Mob
        for ((slot, stack) in slots) runCatching { mob.equipment.setItem(slot, stack) }
    }
}
