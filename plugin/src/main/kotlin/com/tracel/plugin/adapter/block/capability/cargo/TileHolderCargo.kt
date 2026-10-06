package com.tracel.plugin.adapter.block.capability.cargo

import com.tracel.plugin.adapter.block.CargoSlots
import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.block.BlockState

/** Live inventory of a [TileStateInventoryHolder]. */
internal object TileHolderCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (!TileInventory.matches(state)) return null
        val inv = (state as TileStateInventoryHolder).inventory
        return InventorySlots(inv)
    }
}
