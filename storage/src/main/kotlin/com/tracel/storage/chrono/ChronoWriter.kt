package com.tracel.storage.chrono

import com.tracel.storage.StorageUnit
import com.tracel.storage.UnitParticipant
import com.tracel.storage.chrono.bits.RadixSort

/**
 * What one unit of work changed on one lane, gathered row by row and written as one fragment
 * per tile when the unit commits.
 */
internal class ChronoWriter(
    private val lane: Lane,
    private val windowMillis: Long,
    private val note: (Tile, Lane, Int) -> Unit,
) : UnitParticipant {
    private val rows = RowsBuilder(256)
    private var tiles = LongArray(256)

    var highestSeq: Long = Long.MIN_VALUE; private set

    fun add(
        tile: Tile,
        seq: Long,
        millis: Long,
        cell: Int,
        actor: Int,
        kind: Int,
        before: Int,
        after: Int,
        be: ByteArray?,
        ae: ByteArray?
    ) {
        note(tile, seq)
        rows.add(seq, millis, cell, actor, kind, before, after, be, ae)
    }

    fun addEntity(
        tile: Tile,
        seq: Long,
        millis: Long,
        cell: Int,
        actor: Int,
        kind: Int,
        type: Int,
        high: Long,
        low: Long,
        be: ByteArray?,
        ae: ByteArray?
    ) {
        note(tile, seq)
        rows.addEntity(seq, millis, cell, actor, kind, type, high, low, be, ae)
    }

    fun addFlow(
        tile: Tile,
        seq: Long,
        millis: Long,
        cell: Int,
        causedBy: Int,
        kind: Int,
        item: Int,
        source: Int,
        destination: Int,
        flowIndex: Int,
        quantity: Long,
        txn: Long,
        lots: ByteArray?,
        home: Tile,
    ) {
        note(tile, seq)
        rows.addFlow(seq, millis, cell, causedBy, kind, item, source, destination, flowIndex, quantity, txn, lots, home)
    }

    fun holds(seq: Long): Boolean {
        if (seq > highestSeq) return false
        val all = rows.rows()
        for (i in 0 until all.size) if (all.seq[i] == seq) return true
        return false
    }

    private fun note(tile: Tile, seq: Long) {
        val i = rows.size
        if (i == tiles.size) tiles = tiles.copyOf(i shl 1)
        tiles[i] = tile.packed
        if (seq > highestSeq) highestSeq = seq
    }

    override fun savepoint(): Int = rows.size

    override fun rollbackTo(savepoint: Int) {
        rows.truncate(savepoint)
    }

    override fun beforeCommit(unit: StorageUnit) {
        val n = rows.size
        if (n == 0) return
        val all = rows.rows()
        val keys = LongArray(n) { tiles[it] }
        val order = RadixSort.ascending(keys, n)
        val touched = LinkedHashMap<Long, Int>()
        var from = 0
        while (from < n) {
            val tile = keys[order[from]]
            var until = from + 1
            while (until < n && keys[order[until]] == tile) until++
            val group = order.copyOfRange(from, until)
            val byWindow = group.groupBy { Math.floorDiv(all.millis[it], windowMillis) }
            for ((window, indexes) in byWindow) {
                val picked = all.pick(indexes.toIntArray())
                val sorted = picked.sorted()
                val bytes = lane.encode(sorted, window)
                unit.put(ChronoKeys.fragment(Tile(tile), lane, sorted.seq[sorted.size - 1]), bytes)
                touched.merge(tile, bytes.size, Int::plus)
            }
            from = until
        }
        unit.afterCommit { for ((tile, bytes) in touched) note(Tile(tile), lane, bytes) }
    }
}
