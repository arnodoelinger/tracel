package com.tracel.plugin.adapter.block.capability.cargo

import org.bukkit.block.data.BlockData

/** A [BlockData] flag that claims an item is inside, with the item itself gone. */
internal fun interface CargoClaim {
    /** Clears the claim from the given [data]. */
    fun clear(data: BlockData): Boolean
}
