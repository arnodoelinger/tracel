package com.tracel.plugin.listener.support

import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.util.ExpiringMap
import org.bukkit.block.Block

/** Who fired a dispenser, by the cell in front of it, where the mob from its spawn egg appears. */
internal object DispensedBy {
    private const val TTL_MS = 2_000L

    private val byCell = ExpiringMap<BlockPos, HolderId>(TTL_MS, 4_096)

    fun fired(front: Block, by: HolderId) {
        byCell.put(front.toBlockPos(), by)
    }

    fun at(cell: Block): HolderId? = byCell[cell.toBlockPos()]
}
