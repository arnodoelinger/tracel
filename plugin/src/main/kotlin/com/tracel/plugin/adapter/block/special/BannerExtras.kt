package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockExtras
import com.tracel.plugin.adapter.block.capability.extras.PlacementItem
import org.bukkit.block.Banner
import org.bukkit.block.BlockState
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta

/**
 * Banners.
 *
 * @see Banner
 * @see BannerMeta
 */
@Unstable
internal object BannerExtras {
    fun of(state: BlockState): BlockExtras? {
        if (state !is Banner) return null
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta as? BannerMeta ?: return null
        meta.patterns = state.patterns
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}
