package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitUnpack
import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.RadixSort

/** What every encoder shares. */
abstract class AbstractCapsuleEncoder(private val kind: CapsuleKind, private val magic: Int) {
    protected val sink = ByteWriter(16 * 1024)
    private val offsets = IntArray(CapsuleSection.COUNT + 1)
    private var window = 0L
    private var lastSeqAtLeast = Long.MIN_VALUE
    private var maxMillisAtLeast = Long.MIN_VALUE

    /** What one pass over the rows found, which the header and the cell table both need. */
    protected class Extent(val yFloor: Int, val minMillis: Long, val maxMillis: Long) {
        val floorKey: Int get() = yFloor shl 8
    }

    /** Encodes [rows], which may come in any order, as one capsule of [window]. */
    fun encode(
        rows: Rows,
        window: Long,
        lastSeqAtLeast: Long = Long.MIN_VALUE,
        maxMillisAtLeast: Long = Long.MIN_VALUE
    ): ByteArray {
        require(rows.size > 0) { "a capsule of nothing says nothing." }
        this.window = window
        this.lastSeqAtLeast = lastSeqAtLeast
        this.maxMillisAtLeast = maxMillisAtLeast
        sink.reset()
        clear()
        val n = rows.size
        val order = if (rows.isSorted()) IntArray(n) { it } else RadixSort.ascending(rows.seq, n)
        write(rows, order, scan(rows, order))
        offsets[CapsuleSection.COUNT] = sink.size
        for (s in offsets.indices) sink.patchU32(4 + s * 4, offsets[s])
        return sink.toByteArray(BitUnpack.PADDING)
    }

    /** Forgets the last capsule's palettes. */
    protected abstract fun clear()

    /** Writes the whole capsule, [order] being the rows in sequence order. */
    protected abstract fun write(rows: Rows, order: IntArray, extent: Extent)

    /** The prelude, with the offsets left to [encode], then the header; [counts] follow the cell count. */
    protected fun writeHeader(
        flags: Int,
        rows: Rows,
        order: IntArray,
        extent: Extent,
        cellCount: Int,
        vararg counts: Int
    ) {
        sink.u8(magic)
        sink.u8(kind.version)
        sink.u8(flags)
        sink.u8(CapsuleSection.COUNT)
        repeat(CapsuleSection.COUNT + 1) { sink.u32(0) }
        val first = rows.seq[order[0]]
        sink.varint(rows.size.toLong())
        sink.varint(cellCount.toLong())
        for (count in counts) sink.varint(count.toLong())
        sink.varint(first)
        sink.varint(maxOf(rows.seq[order[order.size - 1]], lastSeqAtLeast) - first)
        sink.signed(extent.minMillis)
        sink.varint(maxOf(extent.maxMillis, maxMillisAtLeast) - extent.minMillis)
        sink.signed(window)
        sink.signed(extent.yFloor.toLong())
    }

    /** Says [section] starts here. */
    protected fun mark(section: CapsuleSection) {
        offsets[section.ordinal] = sink.size
    }

    /** A sorted palette, as [Parts.writePalette] has it. */
    protected fun writePalette(sorted: IntArray) = Parts.writePalette(sink, sorted)

    /** Keys relative to the floor, so a ground-level build pays for its own sixteen rows, not for -64. */
    protected fun writeCellTable(sorted: IntArray, extent: Extent) = Parts.writeCells(sink, sorted, extent.floorKey)

    /** The sequence and the clock, as gaps. */
    protected fun writeTimeline(rows: Rows, order: IntArray, extent: Extent) {
        val n = order.size
        Parts.writeGaps(sink, n, signed = false) { rows.seq[order[it]] }
        sink.varint(rows.millis[order[0]] - extent.minMillis)
        Parts.writeGaps(sink, n, signed = true) { rows.millis[order[it]] }
    }

    private fun scan(rows: Rows, order: IntArray): Extent {
        var yFloor = Int.MAX_VALUE
        var minMillis = Long.MAX_VALUE
        var maxMillis = Long.MIN_VALUE
        for (r in order) {
            val y = Cell(rows.cell[r]).y
            if (y < yFloor) yFloor = y
            val m = rows.millis[r]
            if (m < minMillis) minMillis = m
            if (m > maxMillis) maxMillis = m
        }
        return Extent(yFloor, minMillis, maxMillis)
    }
}
