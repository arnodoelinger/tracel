package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurface
import com.tracel.plugin.adapter.block.capability.cargo.InventorySlots
import org.bukkit.block.BlockState
import org.bukkit.block.Chest

/**
 * Half of a double chest.
 *
 * @see Chest
 */
@Unstable
internal object ChestHalfCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Chest) return null
        return InventorySlots(state.blockInventory)
    }
}
