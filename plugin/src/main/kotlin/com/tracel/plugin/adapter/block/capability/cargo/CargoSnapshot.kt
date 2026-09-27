package com.tracel.plugin.adapter.block.capability.cargo

import com.tracel.plugin.adapter.block.special.ChiseledBookshelfCapture
import com.tracel.plugin.adapter.block.special.JukeboxRecord
import org.bukkit.block.Beehive
import org.bukkit.block.BlockState
import org.bukkit.block.BrushableBlock
import org.bukkit.block.Campfire
import org.bukkit.inventory.ItemStack

/**
 * Empties cargo on an unplaced tile snapshot so extras NBT does not also
 * carry items the ledger tracks.
 */
internal object CargoSnapshot {
    @Suppress("UsePropertyAccessSyntax")
    fun empty(state: BlockState) {
        if (JukeboxRecord.clear(state)) return
        if (ChiseledBookshelfCapture.skipTileExtras(state)) return
        if (state is Campfire) {
            for (i in 0 until state.size) state.setItem(i, ItemStack.empty())
            return
        }
        if (state is Beehive) state.clearEntities()
        if (state is BrushableBlock) {
            state.setItem(ItemStack.empty())
            return
        }
        TileInventory.clearSnapshot(state)
    }
}
