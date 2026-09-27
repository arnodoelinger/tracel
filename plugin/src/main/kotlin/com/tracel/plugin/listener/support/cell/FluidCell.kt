package com.tracel.plugin.listener.support.cell

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.ExpiringMap

// TODO: rewrite

@Unstable
internal object FluidCell {
    private const val MAX_REMEMBERED = 250_000
    const val MAX_SETTLING_MILLIS = 120_000L
    private const val CLAIM_MILLIS = 30_000L

    private val until = ExpiringMap<BlockPos, Long>(MAX_SETTLING_MILLIS, MAX_REMEMBERED)
    private val claimed = ExpiringMap<BlockPos, Unit>(CLAIM_MILLIS, MAX_REMEMBERED)

    fun disturb(cells: Collection<BlockPos>, now: Long = System.currentTimeMillis()) {
        if (cells.isEmpty()) return
        until.putAll(cells, now + MAX_SETTLING_MILLIS)
    }

    fun onFlow(from: BlockPos, to: BlockPos, now: Long = System.currentTimeMillis()) {
        if (from in claimed) {
            claimed.put(to, Unit)
            until.remove(to)
            return
        }
        val deadline = maxOf(until[from] ?: 0L, until[to] ?: 0L)
        if (deadline <= now) return
        until.put(from, deadline)
        until.put(to, deadline)
    }

    fun inherit(pos: BlockPos, neighbours: Collection<BlockPos>, now: Long = System.currentTimeMillis()) {
        if (pos in claimed || isDisturbed(pos, now)) return
        var deadline = 0L
        for (neighbour in neighbours) deadline = maxOf(deadline, until[neighbour] ?: 0L)
        if (deadline <= now) return
        until.put(pos, deadline)
    }

    fun claim(cells: Collection<BlockPos>) {
        for (cell in cells) {
            until.remove(cell)
            claimed.put(cell, Unit)
        }
    }

    fun isDisturbed(pos: BlockPos, now: Long = System.currentTimeMillis()): Boolean =
        pos !in claimed && (until[pos] ?: 0L) > now

    fun forgetAll() {
        until.clear()
        claimed.clear()
    }
}
