package com.tracel.plugin.adapter.block.capability.cargo

import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.BrewingStand
import org.bukkit.block.data.type.ChiseledBookshelf
import org.bukkit.block.data.type.Jukebox
import org.bukkit.block.data.type.Lectern

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
