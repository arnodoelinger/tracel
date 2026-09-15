package com.tracel.plugin.listener.support

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.ExpiringMap

// TODO: rewrite

@Unstable
internal object FluidDisturbance {
    private const val TTL_MS = 30_000L
    private const val MAX_REMEMBERED = 250_000
    const val MAX_SETTLING_MILLIS = 120_000L

    private val until = ExpiringMap<BlockPos, Long>(TTL_MS, MAX_REMEMBERED)

    fun disturb(cells: Collection<BlockPos>, now: Long = System.currentTimeMillis()) {
        if (cells.isEmpty()) return
        until.putAll(cells, now + MAX_SETTLING_MILLIS)
    }

    fun onFlow(from: BlockPos, to: BlockPos, now: Long = System.currentTimeMillis()) {
        val deadline = maxOf(until[from] ?: 0L, until[to] ?: 0L)
        if (deadline <= now) return
        until.put(from, deadline)
        until.put(to, deadline)
    }

    fun inherit(pos: BlockPos, neighbours: Collection<BlockPos>, now: Long = System.currentTimeMillis()) {
        if (isDisturbed(pos, now)) return
        var deadline = 0L
        for (neighbour in neighbours) deadline = maxOf(deadline, until[neighbour] ?: 0L)
        if (deadline <= now) return
        until.put(pos, deadline)
    }

    fun claim(cells: Collection<BlockPos>) {
        for (cell in cells) until.remove(cell)
    }

    fun isDisturbed(pos: BlockPos, now: Long = System.currentTimeMillis()): Boolean =
        (until[pos] ?: 0L) > now

    fun forgetAll() {
        until.clear()
    }
}
