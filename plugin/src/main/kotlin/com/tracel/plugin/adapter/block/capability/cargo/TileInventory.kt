package com.tracel.plugin.adapter.block.capability.cargo

import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.block.BlockState
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * Snapshot inventory of a [TileStateInventoryHolder].
 *
 * Live contents live on [TileStateInventoryHolder.inventory] and are captured
 * as cargo via [TileHolderCargo]. This object only touches
 * [TileStateInventoryHolder.snapshotInventory]: the copy that would otherwise
 * ride along in block-entity extras.
 *
 * @see CargoSurface
 * @see TileHolderCargo
 */
internal object TileInventory {
    /** Whether this state has a Paper snapshot inventory. */
    fun matches(state: BlockState): Boolean = state is TileStateInventoryHolder

    /**
     * The holder's snapshot, or none.
     *
     * Unplaced copies after [BlockState.copy] still expose this.
     */
    fun snapshotOf(state: BlockState): Inventory? =
        (state as? TileStateInventoryHolder)?.snapshotInventory

    /** Empty every snapshot slot. No-op when [matches] is false. */
    fun clearSnapshot(state: BlockState) {
        val inv = snapshotOf(state) ?: return
        clearSlots(inv)
    }

    /** Write empty stacks into every index. */
    fun clearSlots(inventory: Inventory) {
        for (i in 0 until inventory.size) inventory.setItem(i, ItemStack.empty())
    }
}
