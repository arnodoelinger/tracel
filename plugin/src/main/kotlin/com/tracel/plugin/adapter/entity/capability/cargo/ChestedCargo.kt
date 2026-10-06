package com.tracel.plugin.adapter.entity.capability.cargo

import com.tracel.plugin.specifics.entity.MOUNT_CHEST
import org.bukkit.entity.ChestedHorse
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * The chest strapped to the horse with a chest.
 *
 * @see [ChestedHorse]
 */
internal object ChestedCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is ChestedHorse

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        if ((entity as ChestedHorse).isCarryingChest) add(ItemStack(MOUNT_CHEST))
    }

    override fun empty(entity: Entity) {
        runCatching { (entity as ChestedHorse).isCarryingChest = false }
    }

    override fun save(entity: Entity): Any = (entity as ChestedHorse).isCarryingChest

    override fun restore(entity: Entity, saved: Any?) {
        val chested = saved as? Boolean ?: return
        runCatching { (entity as ChestedHorse).isCarryingChest = chested }
    }

    override fun resync(entity: Entity, plugin: Plugin) {
        entity.hideThenShow(plugin)
    }
}
