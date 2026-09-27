package com.tracel.plugin.listener.support.cell

import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.util.ExpiringMap
import org.bukkit.block.Block
import java.util.*

/** Who lit a fire, carried along as it spreads, so the house it burns down is theirs to roll back. */
internal object FireCell {
    private const val TTL_MS = 120_000L
    private const val MAX_REMEMBERED = 100_000

    private val byCell = ExpiringMap<BlockPos, UUID>(TTL_MS, MAX_REMEMBERED)

    fun lit(block: Block, player: UUID) {
        byCell.put(block.toBlockPos(), player)
    }

    fun at(block: Block?): UUID? = block?.let { byCell[it.toBlockPos()] }
}
