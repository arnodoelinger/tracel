package com.tracel.plugin.specifics.block

import org.bukkit.block.Block
import org.bukkit.block.BrewingStand
import org.bukkit.block.Campfire
import org.bukkit.block.Furnace

/** Whether what this tile holds besides its state is only a clock: cook time, brew time, fuel left. */
internal fun Block.ticksOnly(): Boolean = when (runCatching { getState(false) }.getOrNull()) {
    is Furnace, is BrewingStand, is Campfire -> true
    else -> false
}
