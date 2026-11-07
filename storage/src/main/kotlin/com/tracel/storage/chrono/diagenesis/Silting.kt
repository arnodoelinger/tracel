package com.tracel.storage.chrono.diagenesis

import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.RowsBuilder
import com.tracel.storage.chrono.Tile

/** New into silt: a tile's fragments settle into the capsules of their window. */
internal class Silting(
    private val filer: Filer,
    private val settings: DiagenesisSettings,
    private val capsuleRows: Int,
    private val ledger: Ledger,
    private val tally: Tally,
) {
    /** Says how many rows went in; the ledger forgets the tile once [unit] commits. */
    fun run(unit: StorageUnit, tile: Tile, lane: Lane): Int {
        val key = tile.packed to lane
        val seen = ledger.get(key)
        val fragments = filer.fragments(unit, tile, lane)
        if (fragments.isEmpty()) {
            unit.afterCommit { ledger.settle(key, seen) }
            return 0
        }
        val capsules = filer.capsules(unit, tile, lane)
        val byWindow = LinkedHashMap<Long, Group>()
        for (held in fragments) {
            val rows = held.view.rows()
            val group = byWindow.getOrPut(held.view.window) { Group(RowsBuilder(maxOf(rows.size, 16))) }
            for (r in 0 until rows.size) group.builder.add(rows, r)
            group.bytes += held.size
            unit.delete(held.key)
        }
        var rowsIn = 0
        var written = 0L
        var newest = 0L
        for ((window, group) in byWindow) {
            var total = group.builder.size
            var weight = group.bytes
            for (peer in capsules.filter { it.view.window == window }.sortedBy { it.size }) {
                if (peer.size > settings.tier * weight || total + peer.rows > capsuleRows) continue
                val rows = peer.view.rows()
                for (r in 0 until rows.size) group.builder.add(rows, r)
                filer.forget(unit, tile, lane, peer)
                total += peer.rows
                weight += peer.size
            }
            val sorted = group.builder.rows().sorted()
            val bytes = filer.lay(unit, tile, lane, window, sorted, capsuleRows)
            written += bytes
            rowsIn += sorted.size
            newest = bytes
        }
        tally.wrote(Generation.SILT, written)
        ledger.tail(key, newest)
        unit.afterCommit { ledger.settle(key, seen) }
        return rowsIn
    }

    private class Group(val builder: RowsBuilder) {
        var bytes = 0L
    }
}
