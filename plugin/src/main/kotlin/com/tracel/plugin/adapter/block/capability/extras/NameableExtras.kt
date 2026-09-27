package com.tracel.plugin.adapter.block.capability.extras

import com.tracel.model.world.block.BlockExtras
import org.bukkit.Nameable
import org.bukkit.block.BlockState
import org.bukkit.inventory.ItemStack

/** Just the custom name, when the tile cannot be serialized in full. */
internal object NameableExtras {
    fun of(state: BlockState): BlockExtras? {
        val nameable = state as? Nameable ?: return null
        if (nameable.customName() == null) return null
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta ?: return null
        nameable.customName()?.let { meta.customName(it) }
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}
