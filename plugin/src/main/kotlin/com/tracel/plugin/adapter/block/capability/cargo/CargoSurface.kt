package com.tracel.plugin.adapter.block.capability.cargo

import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.special.*
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
    /** @return the slots this tile owns, or `null` if none. */
    fun of(state: BlockState): CargoSlots?
}

/** Inventory slots. */
internal class InventorySlots(private val inventory: Inventory) : CargoSlots {
    override val size: Int get() = inventory.size
    override fun get(slot: Int): ItemStack? = inventory.getItem(slot)
    override fun set(slot: Int, stack: ItemStack?) = inventory.setItem(slot, stack)
}
