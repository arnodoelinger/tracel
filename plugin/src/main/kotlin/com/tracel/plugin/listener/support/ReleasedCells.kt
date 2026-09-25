package com.tracel.plugin.listener.support

import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.util.ExpiringSet
import org.bukkit.block.Block
import com.destroystokyo.paper.event.block.BlockDestroyEvent

/**
 * Cells whose drop a specific listener already opened a claim window for, so the catch-all
 * [BlockDestroyEvent] does not open a second one for the same block.
 */
internal object ReleasedCells {
    private const val TTL_MS = 1_000L

    private val cells = ExpiringSet<BlockPos>(TTL_MS)

    /** @return `true` the first time [block] is claimed within the window. */
    fun claim(block: Block): Boolean = cells.add(block.toBlockPos())
}
