package com.tracel.plugin.adapter.block.capability.cargo

import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.special.*
import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.block.BlockState
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * Slots this tile owns, or none.
 *
 * First match wins. A break only drops what died with this block — the merged
 * double-chest inventory here would dump the other half onto the floor.
 */
internal fun interface CargoSurface {
    fun of(state: BlockState): CargoSlots?
}

/** Live inventory of a [TileStateInventoryHolder]. */
internal object TileHolderCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (!TileInventory.matches(state)) return null
        val inv = (state as TileStateInventoryHolder).inventory
        return InventorySlots(inv)
    }
}

/** Inventory slots. */
internal class InventorySlots(private val inventory: Inventory) : CargoSlots {
    override val size: Int get() = inventory.size
    override fun get(slot: Int): ItemStack? = inventory.getItem(slot)
    override fun set(slot: Int, stack: ItemStack?) = inventory.setItem(slot, stack)
}

/** Cargo surfaces, most specific first. */
internal object CargoSurfaces {
    private val all: List<CargoSurface> = listOf(
        JukeboxCargo,
        LecternCargo,
        BookshelfCargo,
        ChestHalfCargo,
        TileHolderCargo,
        CampfireCargo,
        BrushableCargo,
    )

    fun of(state: BlockState): CargoSlots? {
        for (surface in all) surface.of(state)?.let { return it }
        return null
    }
}
