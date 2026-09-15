package com.tracel.plugin.adapter.block.capability.extras

import com.tracel.model.world.block.BlockExtras
import org.bukkit.Nameable
import org.bukkit.block.BlockState
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * Tile extras via the placement item's [BlockStateMeta].
 *
 * Signs, skulls, spawners, etc.
 *
 * Failures fall back to the name only.
 *
 * @see [BlockStateMeta]
 */
internal object BlockStateMetaExtras {
    fun of(state: BlockState): BlockExtras? {
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta as? BlockStateMeta ?: return NameableExtras.of(state)
        meta.blockState = state
        (state as? Nameable)?.customName()?.let { meta.customName(it) }
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}
