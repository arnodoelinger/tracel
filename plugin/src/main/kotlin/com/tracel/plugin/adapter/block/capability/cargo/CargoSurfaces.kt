package com.tracel.plugin.adapter.block.capability.cargo

import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.special.*
import org.bukkit.block.BlockState

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
