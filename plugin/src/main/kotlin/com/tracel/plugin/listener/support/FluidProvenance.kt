package com.tracel.plugin.listener.support

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.util.ExpiringMap
import org.bukkit.block.Block

// TODO: rewrite

@Unstable
internal object FluidProvenance {
    private const val TTL_MS = 30_000L
    private const val MAX_REMEMBERED = 250_000

    private val rootOf = ExpiringMap<BlockPos, BlockPos>(TTL_MS, MAX_REMEMBERED)

    fun onFlow(from: Block, to: Block) {
        val fromPos = from.toBlockPos()
        rootOf.put(to.toBlockPos(), rootOf[fromPos] ?: fromPos)
    }

    fun rootOf(pos: BlockPos): BlockPos? = rootOf[pos]
}
