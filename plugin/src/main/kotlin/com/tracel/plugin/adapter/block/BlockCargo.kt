package com.tracel.plugin.adapter.block

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurfaces
import com.tracel.plugin.adapter.block.special.BookshelfCargo
import com.tracel.plugin.adapter.block.special.CampfireCargo
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.listener.material.place.ContainerListener
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack

/** Ledger-tracked slots this tile owns. */
interface CargoSlots {
    val size: Int
    fun get(slot: Int): ItemStack?
    fun set(slot: Int, stack: ItemStack?)
}

/** Live cargo on this block, or `null`. */
fun Block.cargoSlots(): CargoSlots? {
    if (!type.mayHaveBlockEntity(this)) return null
    return CargoSurfaces.of(getState(false))
}

/** Pushes restored cargo to nearby players. Occupancy tiles need a block-state update. */
fun Block.resyncCargo() {
    CampfireCargo.resync(this)
    BookshelfCargo.sync(this)
}

/** @return whether this cargo holds any items. */
fun CargoSlots.holdsAnything(): Boolean =
    (0 until size).any { slot -> get(slot).isReal() }

/** @return a snapshot of the cargo, with `null`s for empty slots. */
fun CargoSlots.snapshot(): List<ItemStack?> =
    List(size) { slot -> get(slot)?.takeIf { it.isReal() }?.clone() }

/**
 * Read then clear, same tick, before vanilla spills.
 *
 * @see ContainerListener
 */
fun CargoSlots.takeAll(): List<ItemStack> {
    val removed = ArrayList<ItemStack>(size)
    // Don't write this as buildList { for (slot in indices) }. The lambda's
    // receiver is the list being built, so indices / size are the empty result
    // and the chest is never emptied; vanilla drops the books, the ledger never
    // saw them leave, and the next rollback is "insufficient balance".
    for (slot in 0 until size) {
        val stack = get(slot).takeIf { it.isReal() } ?: continue
        removed += stack.clone()
        set(slot, null)
    }
    return removed
}

/** @return whether this block has a container. */
fun Block.container(): Container? {
    if (!type.mayHaveBlockEntity(this)) return null
    return getState(false) as? Container
}

/** @return whether this block has cargo. */
fun Block.cargoTotals(): Map<ItemKey, Long>? {
    if (!type.mayHaveBlockEntity(this)) return null
    val slots = CargoSurfaces.of(getState(false)) ?: return null
    return (0 until slots.size).map { slots.get(it) }.toItemTotals()
}

/** @return whether this block has anything. */
private fun ItemStack?.isReal(): Boolean =
    this != null && !isEmpty && !type.isAir
