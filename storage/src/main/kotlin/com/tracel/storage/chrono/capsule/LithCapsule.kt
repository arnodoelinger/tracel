package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.SegmentReader
import com.tracel.storage.chrono.lith.Bursts
import com.tracel.storage.chrono.lith.LithCodec
import com.tracel.storage.chrono.lith.VoidCodec
import com.tracel.storage.chrono.lith.Voids
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A block capsule pressed for the cold layers: the header in the clear,
 * the rows range-coded ([LithCodec]).
 *
 * @see [LithCodec]
 */
class LithCapsule(override val bytes: MemorySegment) : CapsuleView {
    override val lane: Lane get() = Lane.BLOCKS
    override val rowCount: Int
    override val cellCount: Int
    override val firstSeq: Long
    override val lastSeq: Long
    override val minMillis: Long
    override val maxMillis: Long
    override val window: Long
    override val actors: IntArray
    override val kinds: IntArray

    private val flags: Int
    private val yFloor: Int
    private val ySpan: Int
    private val firstMillis: Long
    private val voidsAt: Long
    private val voidsEnd: Long
    private val bodyAt: Long
    private val bodyEnd: Long

    init {
        val c = SegmentReader(bytes)
        val magic = c.u8()
        check(magic == Lane.BLOCKS.magic) { "not a block capsule: starts with 0x${magic.toString(16)}." }
        val version = c.u8()
        check(version == VERSION) { "lithified capsule v$version, this build reads v$VERSION." }
        flags = c.u8()
        rowCount = c.varintInt()
        cellCount = c.varintInt()
        firstSeq = c.varint()
        lastSeq = firstSeq + c.varint()
        minMillis = c.signed()
        maxMillis = minMillis + c.varint()
        window = c.signed()
        yFloor = c.signed().toInt()
        ySpan = c.varintInt()
        firstMillis = minMillis + c.varint()
        actors = readPalette(c)
        kinds = readPalette(c)
        if (flags and FLAG_VOIDS != 0) {
            val voidsLength = c.varint()
            voidsAt = c.at
            voidsEnd = voidsAt + voidsLength
            c.at = voidsEnd
        } else {
            voidsAt = 0
            voidsEnd = 0
        }
        val length = c.varint()
        bodyAt = c.at
        bodyEnd = bodyAt + length
    }

    val voids: Voids by lazy {
        if (flags and FLAG_VOIDS == 0) Voids.NONE else VoidCodec.decode(bytes, voidsAt, voidsEnd, minMillis)
    }

    private val decoded: Rows by lazy {
        val rows = LithCodec.decode(
            bytes, bodyAt, bodyEnd, rowCount, yFloor, ySpan, firstSeq, firstMillis, flags and FLAG_RAYS != 0, voids,
        )
        if (flags and FLAG_EXTRAS == 0) rows else withExtras(rows)
    }

    @Suppress("CanBeVal")
    override val cells: IntArray by lazy {
        val all = decoded.cell.copyOf()
        all.sort()
        var n = 0
        for (i in all.indices) if (n == 0 || all[n - 1] != all[i]) all[n++] = all[i]
        all.copyOf(n)
    }

    override fun indexOfCell(cell: Cell): Int = cells.binarySearch(cell.packed).coerceAtLeast(-1)

    override fun seqs(): LongArray = decoded.seq.copyOf()

    override fun millis(): LongArray = decoded.millis.copyOf()

    override fun rows(): Rows = decoded

    private fun withExtras(rows: Rows): Rows {
        val c = SegmentReader(bytes, bodyEnd)
        val before = arrayOfNulls<ByteArray>(rowCount)
        val after = arrayOfNulls<ByteArray>(rowCount)
        var row = -1
        repeat(c.varintInt()) {
            row += c.varintInt() + 1
            val side = c.u8()
            val blob = c.bytes(c.varintInt())
            when (side) {
                BEFORE -> before[row] = blob
                AFTER -> after[row] = blob
                BOTH_SAME -> {
                    before[row] = blob
                    after[row] = blob
                }

                else -> {
                    before[row] = blob
                    after[row] = c.bytes(c.varintInt())
                }
            }
        }
        return Rows(rows.size, rows.seq, rows.millis, rows.cell, rows.actor, rows.kind, rows.before, rows.after, before, after)
    }

    companion object {
        const val VERSION: Int = 0x81
        private const val FLAG_EXTRAS = 1
        private const val FLAG_RAYS = 2
        private const val FLAG_VOIDS = 4
        private const val BEFORE = 1
        private const val AFTER = 2
        private const val BOTH_SAME = 3
        private const val BOTH = 4

        fun isOne(bytes: MemorySegment): Boolean =
            bytes.byteSize() > 1 && bytes.get(ValueLayout.JAVA_BYTE, 1).toInt() and 0xFF == VERSION

        fun encode(
            rows: Rows,
            window: Long,
            lastSeqAtLeast: Long = Long.MIN_VALUE,
            maxMillisAtLeast: Long = Long.MIN_VALUE,
            tile: Tile? = null,
            bursts: Bursts = Bursts.NONE,
            voids: Voids = Voids.NONE,
        ): ByteArray {
            require(rows.size > 0) { "a capsule of nothing says... nothing." }
            val sorted = rows.sorted()
            val n = sorted.size
            var yLo = Int.MAX_VALUE
            var yHi = Int.MIN_VALUE
            var minMillis = Long.MAX_VALUE
            var maxMillis = Long.MIN_VALUE
            var extras = 0
            for (i in 0 until n) {
                val y = Cell(sorted.cell[i]).y
                if (y < yLo) yLo = y
                if (y > yHi) yHi = y
                val m = sorted.millis[i]
                if (m < minMillis) minMillis = m
                if (m > maxMillis) maxMillis = m
                if (blob(sorted.beforeExtrasAt(i)) != null || blob(sorted.afterExtrasAt(i)) != null) extras++
            }
            val hints = tile?.let { LithCodec.RayHints(it.originX, it.originZ, bursts) }
            val plain = LithCodec.encode(sorted, yLo, yHi - yLo + 1, rays = true, hints = hints)

            var body = plain
            var voidBytes: ByteArray? = null
            if (!voids.isEmpty) {
                val kept = voids.only(minMillis, maxMillis, Math.floorDiv(yLo, 16), Math.floorDiv(yHi, 16))
                if (!kept.isEmpty) {
                    val withVoids = LithCodec.encode(sorted, yLo, yHi - yLo + 1, rays = true, hints = hints, voids = kept)
                    val maps = VoidCodec.encode(kept, minMillis)
                    if (withVoids.size + maps.size + 4 < plain.size) {
                        body = withVoids
                        voidBytes = maps
                    }
                }
            }
            val cells = sorted.cell.copyOf(n).also { it.sort() }
            var cellCount = 0
            for (i in cells.indices) if (i == 0 || cells[i] != cells[i - 1]) cellCount++

            val out = ByteWriter(body.size + 64)
            out.u8(Lane.BLOCKS.magic)
            out.u8(VERSION)
            out.u8((if (extras > 0) FLAG_EXTRAS else 0) or FLAG_RAYS or (if (voidBytes != null) FLAG_VOIDS else 0))
            out.varint(n.toLong())
            out.varint(cellCount.toLong())
            val first = sorted.seq[0]
            out.varint(first)
            out.varint(maxOf(sorted.seq[n - 1], lastSeqAtLeast) - first)
            out.signed(minMillis)
            out.varint(maxOf(maxMillis, maxMillisAtLeast) - minMillis)
            out.signed(window)
            out.signed(yLo.toLong())
            out.varint((yHi - yLo + 1).toLong())
            out.varint(sorted.millis[0] - minMillis)
            writePalette(out, sorted.actor, n)
            writePalette(out, sorted.kind, n)
            if (voidBytes != null) {
                out.varint(voidBytes.size.toLong())
                out.bytes(voidBytes)
            }
            out.varint(body.size.toLong())
            out.bytes(body)
            if (extras > 0) {
                out.varint(extras.toLong())
                var previous = -1
                for (i in 0 until n) {
                    val be = blob(sorted.beforeExtrasAt(i))
                    val ae = blob(sorted.afterExtrasAt(i))
                    if (be == null && ae == null) continue
                    out.varint((i - previous - 1).toLong())
                    previous = i
                    val same = be != null && ae != null && be.contentEquals(ae)
                    out.u8(
                        when {
                            same -> BOTH_SAME
                            be != null && ae != null -> BOTH
                            be != null -> BEFORE
                            else -> AFTER
                        }
                    )
                    val firstBlob = be ?: ae!!
                    out.varint(firstBlob.size.toLong())
                    out.bytes(firstBlob)
                    if (!same && be != null && ae != null) {
                        out.varint(ae.size.toLong())
                        out.bytes(ae)
                    }
                }
            }
            return out.toByteArray()
        }

        private fun blob(b: ByteArray?): ByteArray? = b?.takeIf { it.isNotEmpty() }

        private fun writePalette(out: ByteWriter, column: IntArray, n: Int) {
            val sorted = column.copyOf(n).also { it.sort() }
            var count = 0
            for (i in 0 until n) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[count++] = sorted[i]
            out.varint(count.toLong())
            if (count == 0) return
            out.signed(sorted[0].toLong())
            for (i in 1 until count) out.varint((sorted[i].toLong() - sorted[i - 1]) - 1)
        }

        private fun readPalette(c: SegmentReader): IntArray {
            val out = IntArray(c.varintInt())
            if (out.isEmpty()) return out
            out[0] = c.signed().toInt()
            for (i in 1 until out.size) out[i] = (out[i - 1] + c.varint() + 1).toInt()
            return out
        }
    }
}
