package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitPack
import com.tracel.storage.chrono.bits.indexWidth
import com.tracel.storage.chrono.hash.IntIndex
import com.tracel.storage.chrono.sameExtras

/** Turns rows into a block capsule, as [Capsule] lays it out. */
class CapsuleEncoder : AbstractCapsuleEncoder(CapsuleKind.BLOCKS, Lane.BLOCKS.magic) {
    private val states = IntIndex(256)
    private val actors = IntIndex(16)
    private val kinds = IntIndex(16)
    private val cells = IntIndex(256)

    var lastBreaks: Int = 0; private set

    override fun clear() {
        states.clear(); actors.clear(); kinds.clear(); cells.clear()
    }

    override fun write(rows: Rows, order: IntArray, extent: Extent) {
        val n = order.size
        val cellIdx = IntArray(n)
        val beforeIdx = IntArray(n)
        val afterIdx = IntArray(n)
        val actorIdx = IntArray(n)
        val kindIdx = IntArray(n)
        for (k in 0 until n) {
            val r = order[k]
            cellIdx[k] = cells.add(rows.cell[r])
            beforeIdx[k] = states.add(rows.before[r])
            afterIdx[k] = states.add(rows.after[r])
            actorIdx[k] = actors.add(rows.actor[r])
            kindIdx[k] = kinds.add(rows.kind[r])
        }
        val cellRank = cells.ranked()
        val stateRank = states.ranked()
        val actorRank = actors.ranked()
        val kindRank = kinds.ranked()
        val cellCount = cells.size
        val cur = IntArray(cellCount) { -1 }
        val curExtras = arrayOfNulls<ByteArray>(cellCount)
        val entry = IntArray(cellCount)
        val entryExtras = arrayOfNulls<ByteArray>(cellCount)
        val breakRows = IntArray(n)
        val breakStates = IntArray(n)
        val breakExtras = arrayOfNulls<ByteArray>(n)
        var breaks = 0
        var anyExtras = false
        for (k in 0 until n) {
            val r = order[k]
            val c = cellIdx[k]
            val be = rows.beforeExtrasAt(r)
            val ae = rows.afterExtrasAt(r)
            if (be != null || ae != null) anyExtras = true
            if (cur[c] < 0) {
                entry[c] = beforeIdx[k]
                entryExtras[c] = be
            } else if (cur[c] != beforeIdx[k] || !sameExtras(curExtras[c], be)) {
                breakRows[breaks] = k
                breakStates[breaks] = beforeIdx[k]
                breakExtras[breaks] = be
                breaks++
            }
            cur[c] = afterIdx[k]
            curExtras[c] = ae
        }
        lastBreaks = breaks

        writeHeader(if (anyExtras) Capsule.FLAG_EXTRAS else 0, rows, order, extent, cellCount)

        mark(CapsuleSection.SUBJECTS)
        writePalette(stateRank.sorted)
        mark(CapsuleSection.ACTORS)
        writePalette(actorRank.sorted)
        mark(CapsuleSection.KINDS)
        writePalette(kindRank.sorted)

        mark(CapsuleSection.CELLS)
        writeCellTable(cellRank.sorted, extent)
        val bySorted = IntArray(cellCount)
        for (i in 0 until cellCount) bySorted[cellRank.rank[i]] = i
        val stateBits = indexWidth(states.size)
        val column = IntArray(maxOf(n, cellCount))
        for (p in 0 until cellCount) column[p] = stateRank.rank[entry[bySorted[p]]]
        BitPack.ints(sink, column, cellCount, stateBits)

        mark(CapsuleSection.ROWS)
        sink.varint(cellRank.rank[cellIdx[0]].toLong())
        Parts.writeGaps(sink, n, signed = true) { cellRank.rank[cellIdx[it]].toLong() }
        writeTimeline(rows, order, extent)
        for (k in 0 until n) column[k] = actorRank.rank[actorIdx[k]]
        Parts.writeIndexes(sink, column, n, indexWidth(actors.size))
        for (k in 0 until n) column[k] = kindRank.rank[kindIdx[k]]
        Parts.writeIndexes(sink, column, n, indexWidth(kinds.size))
        for (k in 0 until n) column[k] = stateRank.rank[afterIdx[k]]
        Parts.writeIndexes(sink, column, n, stateBits)

        mark(CapsuleSection.TAIL)
        ChainTail.writeBreaks(sink, breaks, breakRows, breakStates, stateRank.rank)
        if (!anyExtras) {
            sink.varint(0)
        } else {
            val entries = ArrayList<ChainTail.Extra>()
            for (p in 0 until cellCount) entryExtras[bySorted[p]]?.let { entries += ChainTail.Extra(ExtraSide.ENTRY, p, it) }
            for (p in 0 until cellCount) curExtras[bySorted[p]]?.let { entries += ChainTail.Extra(ExtraSide.EXIT, p, it) }
            for (k in 0 until n) rows.afterExtrasAt(order[k])?.let { entries += ChainTail.Extra(ExtraSide.AFTER, k, it) }
            for (b in 0 until breaks) breakExtras[b]?.let { entries += ChainTail.Extra(ExtraSide.BREAK, b, it) }
            ChainTail.writeExtras(sink, entries)
        }
    }
}
