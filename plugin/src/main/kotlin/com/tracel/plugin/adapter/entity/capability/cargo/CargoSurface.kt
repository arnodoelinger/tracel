package com.tracel.plugin.adapter.entity.capability.cargo

import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** One way this entity can hold items the ledger already accounts for. */
internal interface CargoSurface {
    /**
     * Whether this surface owns cargo on [entity].
     *
     * First match in [CargoSurfaces] is not exclusive.
     */
    fun matches(entity: Entity): Boolean

    /**
     * Hands each ledger-tracked stack to [add].
     *
     * Skip air and empty; never invent items the ledger does not own.
     */
    fun collect(entity: Entity, add: (ItemStack?) -> Unit)

    /**
     * Strips this surface's cargo from the hull.
     *
     * A no-op leaves NBT items in place; the ledger then delivers a second copy.
     */
    fun empty(entity: Entity)

    /**
     * Snapshot this surface can [restore] later.
     *
     * Shape is private to the implementation.
     */
    fun save(entity: Entity): Any?

    /**
     * Writes [saved] from [save] back onto [entity].
     *
     * Ignore a `null` or wrong-typed snapshot.
     */
    fun restore(entity: Entity, saved: Any?)

    /**
     * Pushes the restored cargo to nearby players.
     *
     * Inventories already update; stands and frames need an extra packet
     * for some reason.
     *
     * Default is nothing.
     */
    fun resync(entity: Entity, plugin: Plugin) { }
}

/** Copies the item stack if it is not `null`, not air, and has a positive amount. */
internal fun ItemStack?.copyIfPresent(): ItemStack? {
    if (this == null || type.isAir || amount <= 0) return null
    return clone()
}

/** Clones [stack] into [add] when it is a real item. */
internal fun addIfPresent(stack: ItemStack?, add: (ItemStack?) -> Unit) {
    val copy = stack.copyIfPresent() ?: return
    add(copy)
}
