package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitPack
import com.tracel.storage.chrono.bits.indexWidth
import com.tracel.storage.chrono.bits.widthOf
import com.tracel.storage.chrono.hash.IntIndex

/** Flow rows into an items capsule, as [ItemsCapsule] lays it out. */
class ItemsCapsuleEncoder : AbstractCapsuleEncoder(CapsuleKind.ITEMS, Lane.ITEMS.magic) {
    private val items = IntIndex(64)
    private val holders = IntIndex(64)
    private val kinds = IntIndex(16)
    private val cells = IntIndex(64)

    override fun clear() {
        items.clear(); holders.clear(); kinds.clear(); cells.clear()
    }

    override fun write(rows: Rows, order: IntArray, extent: Extent) {
        val n = order.size
        val quantity = requireNotNull(rows.uuidHigh) { "flow rows carry a quantity." }
        val txn = requireNotNull(rows.uuidLow) { "flow rows carry a transaction." }
        val destination = requireNotNull(rows.aux) { "flow rows carry a destination and an index." }[0]
        val flowIndex = rows.aux[1]
        val homeWorld = rows.aux[2]
        val homeX = rows.aux[3]
        val homeZ = rows.aux[4]

        val cellIdx = IntArray(n)
        val itemIdx = IntArray(n)
        val actorIdx = IntArray(n)
        val sourceIdx = IntArray(n)
        val destIdx = IntArray(n)
        val kindIdx = IntArray(n)
        var maxFlow = 0
        for (k in 0 until n) {
            val r = order[k]
            cellIdx[k] = cells.add(rows.cell[r])
            itemIdx[k] = items.add(rows.before[r])
            actorIdx[k] = holders.add(rows.actor[r])
            sourceIdx[k] = holders.add(rows.after[r])
            destIdx[k] = holders.add(destination[r])
            kindIdx[k] = kinds.add(rows.kind[r])
            if (flowIndex[r] > maxFlow) maxFlow = flowIndex[r]
        }
        val cellRank = cells.ranked()
        val itemRank = items.ranked()
        val holderRank = holders.ranked()
        val kindRank = kinds.ranked()

        writeHeader(0, rows, order, extent, cells.size)

        mark(CapsuleSection.SUBJECTS)
        writePalette(itemRank.sorted)
        mark(CapsuleSection.ACTORS)
        writePalette(holderRank.sorted)
        mark(CapsuleSection.KINDS)
        writePalette(kindRank.sorted)
        mark(CapsuleSection.CELLS)
        writeCellTable(cellRank.sorted, extent)

        mark(CapsuleSection.ROWS)
        val column = IntArray(n)
        for (k in 0 until n) column[k] = cellRank.rank[cellIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(cells.size))
        writeTimeline(rows, order, extent)
        val holderBits = indexWidth(holders.size)
        for (k in 0 until n) column[k] = holderRank.rank[actorIdx[k]]
        BitPack.ints(sink, column, n, holderBits)
        for (k in 0 until n) column[k] = kindRank.rank[kindIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(kinds.size))
        for (k in 0 until n) column[k] = itemRank.rank[itemIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(items.size))
        for (k in 0 until n) column[k] = holderRank.rank[sourceIdx[k]]
        BitPack.ints(sink, column, n, holderBits)
        for (k in 0 until n) column[k] = holderRank.rank[destIdx[k]]
        BitPack.ints(sink, column, n, holderBits)
        for (k in 0 until n) column[k] = flowIndex[order[k]]
        sink.u8(widthOf(maxFlow))
        BitPack.ints(sink, column, n, widthOf(maxFlow))
        val longs = LongArray(n)
        for (k in 0 until n) longs[k] = quantity[order[k]]
        Parts.writeFramed(sink, longs, n)
        for (k in 0 until n) longs[k] = txn[order[k]]
        Parts.writeFramed(sink, longs, n)
        for (col in arrayOf(homeWorld, homeX, homeZ)) {
            for (k in 0 until n) longs[k] = col[order[k]].toLong()
            Parts.writeFramed(sink, longs, n)
        }

        mark(CapsuleSection.TAIL)
        for (k in 0 until n) sink.varint((rows.beforeExtrasAt(order[k])?.size ?: 0).toLong())
        for (k in 0 until n) rows.beforeExtrasAt(order[k])?.let { sink.bytes(it) }
    }
}
