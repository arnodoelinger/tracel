package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.bits.SegmentReader
import com.tracel.storage.chrono.bits.indexWidth
import com.tracel.storage.chrono.bits.packedBytes
import java.lang.foreign.MemorySegment

/** What every capsule shares. */
abstract class AbstractCapsule(
    final override val bytes: MemorySegment,
    final override val lane: Lane,
    private val kind: CapsuleKind,
    extraCounts: Int = 0,
) : CapsuleView {
    private val offsets = LongArray(CapsuleSection.COUNT + 1)
    final override val rowCount: Int
    final override val cellCount: Int
    final override val firstSeq: Long
    final override val lastSeq: Long
    final override val minMillis: Long
    final override val maxMillis: Long
    final override val window: Long

    protected val counts = IntArray(extraCounts)

    val flags: Int
    val yFloor: Int
    val encodedBytes: Long get() = offsets[CapsuleSection.COUNT]

    init {
        val cursor = SegmentReader(bytes)
        val magic = cursor.u8()
        check(magic == lane.magic) { "not a ${kind.noun}: starts with 0x${magic.toString(16)}." }
        val version = cursor.u8()
        check(version == kind.version) { "${kind.noun} v$version, this build reads v${kind.version}." }
        flags = cursor.u8()
        val sections = cursor.u8()
        check(sections == CapsuleSection.COUNT) { "a ${kind.noun} of $sections sections, expected ${CapsuleSection.COUNT}." }
        for (s in offsets.indices) offsets[s] = cursor.u32().toLong() and 0xFFFF_FFFFL
        rowCount = cursor.varintInt()
        cellCount = cursor.varintInt()
        for (c in counts.indices) counts[c] = cursor.varintInt()
        firstSeq = cursor.varint()
        lastSeq = firstSeq + cursor.varint()
        minMillis = cursor.signed()
        maxMillis = minMillis + cursor.varint()
        window = cursor.signed()
        yFloor = cursor.signed().toInt()
    }

    /** Every actor ID, ascending. */
    override val actors: IntArray by lazy { palette(CapsuleSection.ACTORS) }

    /** Every kind, ascending. */
    override val kinds: IntArray by lazy { palette(CapsuleSection.KINDS) }

    /** The cells, as [Cell.packed], ascending. */
    override val cells: IntArray get() = cellTable.first

    /** The cells, and where the table ends. */
    protected val cellTable: Pair<IntArray, Long> by lazy {
        Parts.readCells(
            bytes,
            offset(CapsuleSection.CELLS),
            cellCount,
            yFloor shl 8
        )
    }

    /** The sequence and clock columns, wherever the lane put them. */
    internal abstract val timeline: Timeline

    /** Bytes [section] takes. */
    fun sectionBytes(section: CapsuleSection): Long = offsets[section.ordinal + 1] - offsets[section.ordinal]

    /** Where [section] starts. */
    protected fun offset(section: CapsuleSection): Long = offsets[section.ordinal]

    /** The sorted palette in [section]. */
    protected fun palette(section: CapsuleSection): IntArray = Parts.readPalette(bytes, offset(section))

    /** Moves [cursor] past a packed column of cell indexes, and says where it started. */
    protected fun skipCellColumn(cursor: SegmentReader): Long {
        val at = cursor.at
        cursor.skip(packedBytes(rowCount, indexWidth(cellCount)).toLong())
        return at
    }

    override fun indexOfCell(cell: Cell): Int = cells.binarySearch(cell.packed).coerceAtLeast(-1)

    override fun seqs(): LongArray = timeline.seq.cumulative(bytes, rowCount, firstSeq, signed = false)

    override fun millis(): LongArray =
        timeline.millis.cumulative(bytes, rowCount, minMillis + timeline.millisFirst, signed = true)
}
