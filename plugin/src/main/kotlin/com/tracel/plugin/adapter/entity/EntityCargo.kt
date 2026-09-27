package com.tracel.plugin.adapter.entity

import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.entity.capability.cargo.CargoSurface
import com.tracel.plugin.adapter.entity.capability.cargo.CargoSurfaces
import com.tracel.plugin.adapter.entity.capability.cargo.ChestedCargo
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Ledger-tracked stacks this hull is carrying. Clones; air skipped. */
internal fun Entity.cargoStacks(): List<ItemStack> = CargoSurfaces.collect(this)

/**
 * Removes all ledger-tracked cargo from this entity.
 *
 * Never skip. A no-op here leaves NBT cargo in the hull; the ledger then delivers a second copy.
 */
internal fun Entity.emptyCargo() = CargoSurfaces.empty(this)

/** Pushes restored cargo to players who already track this entity. */
internal fun Entity.resyncCargoViewers(plugin: Plugin) = CargoSurfaces.resync(this, plugin)

/** Same as [emptyCargo]; named for call sites that strip before a snapshot or spawn. */
internal fun stripCargo(entity: Entity) = entity.emptyCargo()

/** Per-surface snapshots so [restoreCargoSurfaces] can put them back after NBT capture. */
internal fun Entity.saveCargoSurfaces() = CargoSurfaces.save(this)

/**
 * Writes [saved] from [saveCargoSurfaces] back onto this hull.
 *
 * Restore order is the reverse of save: a mule needs the chest before the inventory exists.
 */
internal fun Entity.restoreCargoSurfaces(saved: Map<CargoSurface, Any?>) {
    CargoSurfaces.restore(this, saved)
}

/** Surfaces a pose snapshot strips. */
internal fun Entity.shapeCargoSurfaces(): List<CargoSurface> =
    CargoSurfaces.matching(this).filter { it !== ChestedCargo }

fun Entity.toPlacedEntityId(): HolderId.PlacedEntity = HolderId.PlacedEntity(uniqueId)

fun Entity.toCargoHolderId(): HolderId.Entity = HolderId.Entity(uniqueId)
