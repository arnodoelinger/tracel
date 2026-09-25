package com.tracel.plugin.adapter.entity.capability.state

import com.tracel.plugin.adapter.entity.capability.cargo.EquipmentAsCargo
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import com.tracel.plugin.adapter.entity.capability.cargo.MobEquipmentCargo
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder

/**
 * Armor a mob is wearing, copied onto a hull that we reused.
 *
 * @see [EquipmentAsCargo]
 */
internal object WornEquipmentState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (EquipmentAsCargo.matches(live) || EquipmentAsCargo.matches(ghost)) return
        if (live is InventoryHolder) return
        if (live !is LivingEntity || ghost !is LivingEntity) return
        val worn = ghost.equipment ?: return
        val wearing = live.equipment ?: return
        val owned = (live as? Mob)?.let { MobEquipmentCargo.owned(it) }.orEmpty().toSet()
        for (slot in EquipmentSlot.entries) {
            if (slot in owned) continue
            runCatching { wearing.setItem(slot, worn.getItem(slot)) }
        }
    }
}
