package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockExtras
import com.tracel.plugin.adapter.block.capability.extras.PlacementItem
import org.bukkit.block.BlockState
import org.bukkit.block.Skull
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * Heads. Their item is a [SkullMeta], not a block-state carrier, so the generic path kept nothing
 * and a textured head came back as Steve.
 */
@Unstable
@Suppress("DEPRECATION")
internal object SkullExtras {
    fun of(state: BlockState): BlockExtras? {
        if (state !is Skull) return null
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta as? SkullMeta ?: return null
        meta.ownerProfile = state.ownerProfile
        meta.noteBlockSound = state.noteBlockSound
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}
