package com.tracel.plugin.adapter.entity.capability.cargo

import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * Armor-stand equipment.
 *
 * @see [ArmorStand]
 */
internal object EquipmentAsCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is ArmorStand

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        val equipment = (entity as ArmorStand).equipment
        for (slot in EquipmentSlot.entries) {
            addIfPresent(runCatching { equipment.getItem(slot) }.getOrNull(), add)
        }
    }

    override fun empty(entity: Entity) {
        val equipment = (entity as ArmorStand).equipment
        for (slot in EquipmentSlot.entries) {
            runCatching { equipment.setItem(slot, ItemStack.empty()) }
        }
    }

    override fun save(entity: Entity): Any {
        val equipment = (entity as ArmorStand).equipment
        return EquipmentSlot.entries.associateWith { equipment.getItem(it).clone() }
    }

    override fun restore(entity: Entity, saved: Any?) {
        @Suppress("UNCHECKED_CAST")
        val stand = saved as? Map<EquipmentSlot, ItemStack> ?: return
        val equipment = (entity as ArmorStand).equipment
        for ((slot, stack) in stand) runCatching { equipment.setItem(slot, stack) }
    }

    override fun resync(entity: Entity, plugin: Plugin) {
        val equipment = (entity as ArmorStand).equipment
        val viewers = runCatching { entity.trackedBy }.getOrDefault(emptySet())
        for (slot in EquipmentSlot.entries) {
            val stack = runCatching { equipment.getItem(slot) }.getOrNull() ?: continue
            for (player in viewers) {
                runCatching { player.sendEquipmentChange(entity, slot, stack) }
            }
        }
    }
}
