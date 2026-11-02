package com.tracel.plugin.bukkit.block

import com.tracel.plugin.bukkit.block.*
import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.block.Beehive
import org.bukkit.block.BlockState
import org.bukkit.block.BrushableBlock
import org.bukkit.block.Campfire
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.BrewingStand
import org.bukkit.block.data.type.ChiseledBookshelf
import org.bukkit.block.data.type.Jukebox
import org.bukkit.block.data.type.Lectern
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

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

    /** @return the slots this tile owns, or `null` if none. */
    fun of(state: BlockState): CargoSlots? {
        for (surface in all) surface.of(state)?.let { return it }
        return null
    }
}

/** A [BlockData] flag that claims an item is inside, with the item itself gone. */
internal fun interface CargoClaim {
    /** Clears the claim from the given [data]. */
    fun clear(data: BlockData): Boolean
}

/** Strip every occupancy flag `Paper` currently exposes. */
internal object CargoClaims {
    private val all = listOf(OccupiedSlotsClaim, HasRecordClaim, HasBookClaim, HasBottleClaim)

    /** Clone first. */
    fun cleared(data: BlockData): String {
        val clone = data.clone()
        for (claim in all) claim.clear(clone)
        return clone.asString
    }
}

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

/** Occupied slots on a chiseled bookshelf. */
internal object OccupiedSlotsClaim : CargoClaim {
    override fun clear(data: BlockData): Boolean {
        if (data !is ChiseledBookshelf) return false
        repeat(data.maximumOccupiedSlots) { data.setSlotOccupied(it, false) }
        return true
    }
}

/** Disc present. */
internal object HasRecordClaim : CargoClaim {
    override fun clear(data: BlockData): Boolean {
        if (data !is Jukebox) return false
        data.setHasRecord(false)
        return true
    }
}

/**
 * Book on the lectern.
 *
 * Same story as [HasRecordClaim].
 */
internal object HasBookClaim : CargoClaim {
    override fun clear(data: BlockData): Boolean {
        if (data !is Lectern) return false
        data.setHasBook(false)
        return true
    }
}

/** Bottles in a brewing stand. */
internal object HasBottleClaim : CargoClaim {
    override fun clear(data: BlockData): Boolean {
        if (data !is BrewingStand) return false
        repeat(data.maximumBottles) { data.setBottle(it, false) }
        return true
    }
}

/** Live inventory of a [TileStateInventoryHolder]. */
internal object TileHolderCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (!TileInventory.matches(state)) return null
        val inv = (state as TileStateInventoryHolder).inventory
        return InventorySlots(inv)
    }
}

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
