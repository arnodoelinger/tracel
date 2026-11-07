package com.tracel.storage.chrono.diagenesis

import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.RowsBuilder
import com.tracel.storage.chrono.Tile

/** Silt into stratum, stratum into glacier, glacier into permafrost. */
internal class Lithify(
    private val filer: Filer,
    private val settings: DiagenesisSettings,
    private val windowMillis: Long,
    private val capsuleRows: Int,
    private val ledger: Ledger,
    private val tally: Tally,
) {
    /**
     * Presses what is ripe in [tile] on [lane] at [now]. [retired] worlds ripen all at once.
     *
     * Says the bytes written.
     */
    fun run(unit: StorageUnit, tile: Tile, lane: Lane, now: Long, retired: Boolean): Long {
        val capsules = filer.capsules(unit, tile, lane)
        if (capsules.size < 2) return 0L
        val current = Math.floorDiv(now, windowMillis)
        val pinned = ledger.pinned(tile)
        val groups = HashMap<Pair<Generation, Long>, MutableList<Held>>()
        val windowBytes = HashMap<Long, Long>()
        for (held in capsules) windowBytes.merge(held.view.window, held.size, Long::plus)
        for (held in capsules) {
            if (held.rows * 4 >= capsuleRows * 3) continue
            val window = held.view.window
            if (window >= current && !retired) continue
            fun ripe(span: Int, after: Int): Boolean =
                current - ((Math.floorDiv(window, span.toLong()) + 1) * span - 1) >= after
            val id = when {
                pinned -> Generation.STRATUM to window
                retired || ripe(settings.permafrostSpanWindows, settings.permafrostAfterWindows) ->
                    Generation.PERMAFROST to Math.floorDiv(window, settings.permafrostSpanWindows.toLong())
                ripe(settings.glacierSpanWindows, if (windowBytes.getValue(window) < settings.dustBytes) 1 else settings.glacierAfterWindows) ->
                    Generation.GLACIER to Math.floorDiv(window, settings.glacierSpanWindows.toLong())
                else -> Generation.STRATUM to window
            }
            groups.getOrPut(id) { ArrayList() } += held
        }
        var written = 0L
        for ((id, members) in groups) {
            if (members.size < 2) continue
            val rows = members.sumOf { it.rows }
            if ((rows + capsuleRows - 1) / capsuleRows >= members.size) continue
            val builder = RowsBuilder(maxOf(rows, 16))
            for (held in members) {
                val part = held.view.rows()
                for (r in 0 until part.size) builder.add(part, r)
                filer.forget(unit, tile, lane, held)
            }
            val window = members.minOf { it.view.window }
            val bytes = filer.lay(unit, tile, lane, window, builder.rows().sorted(), capsuleRows)
            tally.wrote(id.first, bytes)
            written += bytes
        }
        return written
    }
}
