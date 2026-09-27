package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.capability.cargo.CargoSurface
import com.tracel.plugin.adapter.entity.capability.cargo.addIfPresent
import com.tracel.plugin.adapter.entity.capability.cargo.hideThenShow
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * The item shown in an item frame.
 *
 * @see ItemFrame
 */
@Unstable
internal object ItemFrameCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is ItemFrame

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        addIfPresent((entity as ItemFrame).item, add)
    }

    override fun empty(entity: Entity) {
        runCatching { (entity as ItemFrame).setItem(ItemStack.empty(), false) }
    }

    override fun save(entity: Entity): Any = (entity as ItemFrame).item.clone()

    override fun restore(entity: Entity, saved: Any?) {
        val item = saved as? ItemStack ?: return
        (entity as ItemFrame).setItem(item, false)
    }

    override fun resync(entity: Entity, plugin: Plugin) {
        entity.hideThenShow(plugin)
    }
}
