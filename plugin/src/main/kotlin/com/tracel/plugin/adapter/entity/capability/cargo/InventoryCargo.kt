package com.tracel.plugin.adapter.entity.capability.cargo

import org.bukkit.entity.Entity
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Whatever [InventoryHolder] currently holds.
 *
 * @see [InventoryHolder]
 */
internal object InventoryCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean =
        entity is InventoryHolder && !EquipmentAsCargo.matches(entity)

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        val holder = entity as InventoryHolder
        for (stack in holder.inventory.contents) addIfPresent(stack, add)
    }

    override fun empty(entity: Entity) {
        val inv = (entity as InventoryHolder).inventory
        for (i in 0 until inv.size) runCatching { inv.setItem(i, ItemStack.empty()) }
    }

    override fun save(entity: Entity): Any =
        (entity as InventoryHolder).inventory.contents.map { it?.clone() }.toTypedArray()

    override fun restore(entity: Entity, saved: Any?) {
        val contents = saved as? Array<*> ?: return
        val items = Array(contents.size) { contents[it] as? ItemStack }
        runCatching { (entity as InventoryHolder).inventory.contents = items }
    }
}
