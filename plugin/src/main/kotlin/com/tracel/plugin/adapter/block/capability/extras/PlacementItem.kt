package com.tracel.plugin.adapter.block.capability.extras

import org.bukkit.Material
import org.bukkit.block.BlockState

/** The item a player would use to place this block. */
internal object PlacementItem {
    fun of(state: BlockState): Material {
        val placed = runCatching { state.blockData.placementMaterial }.getOrNull()
        if (placed != null && placed != Material.AIR && placed.isItem) return placed
        return state.type.takeIf { it.isItem } ?: Material.STONE
    }
}
