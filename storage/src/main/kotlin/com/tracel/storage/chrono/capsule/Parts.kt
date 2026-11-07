package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.bits.*
import java.lang.foreign.MemorySegment

/** The pieces both lanes' capsules are built from. */
object Parts {
    private const val EXCEPTION_BYTES = 3L
    private const val PACKED = 0
    private const val RUNS = 1

    /** A [writeColumn] column: where its packed bits start, and the patches that put the wide values back. */
    class Gaps(
        val floor: Long,
        val bits: Int,
        val at: Long,
        val end: Long,
        private val patchAt: IntArray,
        private val patchHigh: LongArray
    ) {
        fun values(seg: MemorySegment, n: Int): LongArray {
            val out = LongArray(n)
            for (i in 0 until n) out[i] = BitUnpack.get(seg, at, bits, i) + floor
            for (p in patchAt.indices) out[patchAt[p]] += patchHigh[p] shl bits
            return out
        }

        fun cumulative(seg: MemorySegment, rows: Int, start: Long, signed: Boolean): LongArray {
            val out = LongArray(rows)
            out[0] = start
            val gaps = values(seg, rows - 1)
            var acc = start
            for (i in 1 until rows) {
                acc += if (signed) unzigzag(gaps[i - 1]) else gaps[i - 1]
                out[i] = acc
            }
            return out
        }
    }

    /** A [writeIndexes] column. */
    class Indexes(
        private val mode: Int,
        private val at: Long,
        private val bits: Int,
        private val runValue: IntArray,
        private val runLength: IntArray,
        val end: Long
    ) {
        fun decode(seg: MemorySegment, n: Int): IntArray {
            val out = IntArray(n)
            if (mode == PACKED) {
                BitUnpack.ints(seg, at, bits, n, out)
                return out
            }
            var at = 0
            for (r in runValue.indices) {
                out.fill(runValue[r], at, at + runLength[r])
                at += runLength[r]
            }
            return out
        }
    }

    fun writePalette(sink: ByteWriter, sorted: IntArray) {
        sink.varint(sorted.size.toLong())
        if (sorted.isEmpty()) return
        sink.varint(sorted[0])
        for (i in 1 until sorted.size) sink.varint(sorted[i] - sorted[i - 1] - 1)
    }

    fun readPalette(seg: MemorySegment, at: Long): IntArray {
        val cursor = SegmentReader(seg, at)
        val n = cursor.varintInt()
        val out = IntArray(n)
        if (n == 0) return out
        out[0] = cursor.varintInt()
        for (i in 1 until n) out[i] = out[i - 1] + cursor.varintInt() + 1
        return out
    }

    fun writeCells(sink: ByteWriter, sorted: IntArray, floorKey: Int) {
        val keys = IntArray(sorted.size) { sorted[it] - floorKey }
        sink.varint(keys[0].toLong())
        writeColumn(
            sink,
            LongArray(maxOf(keys.size - 1, 0)) { (keys[it + 1] - keys[it] - 1).toLong() },
            maxOf(keys.size - 1, 0)
        )
    }

    fun readCells(seg: MemorySegment, at: Long, count: Int, floorKey: Int): Pair<IntArray, Long> {
        val c = SegmentReader(seg, at)
        var key = c.varintInt()
        val gaps = readColumn(c, maxOf(count - 1, 0)).values(seg, maxOf(count - 1, 0))
        val out = IntArray(count)
        out[0] = key + floorKey
        for (i in 1 until count) {
            key += gaps[i - 1].toInt() + 1
            out[i] = key + floorKey
        }
        return out to c.at
    }

    inline fun writeGaps(sink: ByteWriter, n: Int, signed: Boolean, value: (Int) -> Long) {
        val gaps = LongArray(maxOf(n - 1, 0))
        var previous = value(0)
        for (k in 1 until n) {
            val v = value(k)
            gaps[k - 1] = if (signed) zigzag(v - previous) else v - previous
            previous = v
        }
        writeColumn(sink, gaps, gaps.size)
    }

    fun writeFramed(sink: ByteWriter, values: LongArray, n: Int): Unit = writeColumn(sink, values, n)

    fun writeColumn(sink: ByteWriter, values: LongArray, n: Int) {
        var floor = if (n == 0) 0L else Long.MAX_VALUE
        for (i in 0 until n) if (values[i] < floor) floor = values[i]
        val counts = IntArray(66)
        for (i in 0 until n) counts[widthOf(values[i] - floor)]++
        var best = 64
        var cost = Long.MAX_VALUE
        var above = n
        for (bits in 0..64) {
            above -= counts[bits]
            val bytes = packedBytes(n, bits).toLong() + above * EXCEPTION_BYTES
            if (bytes < cost) {
                cost = bytes
                best = bits
            }
        }
        val bits = best
        var patches = 0
        for (i in 0 until n) if (widthOf(values[i] - floor) > bits) patches++
        sink.signed(floor)
        sink.u8(bits)
        sink.varint(patches.toLong())
        BitPack.framed(sink, values, n, floor, bits)
        var previous = -1
        for (i in 0 until n) {
            if (widthOf(values[i] - floor) <= bits) continue
            sink.varint((i - previous - 1).toLong())
            sink.varint((values[i] - floor) ushr bits)
            previous = i
        }
    }

    fun readColumn(cursor: SegmentReader, n: Int): Gaps {
        val floor = cursor.signed()
        val bits = cursor.u8()
        val patches = cursor.varintInt()
        val at = cursor.at
        cursor.at = at + packedBytes(n, bits)
        val patchAt = IntArray(patches)
        val patchHigh = LongArray(patches)
        var previous = -1
        for (p in 0 until patches) {
            previous += cursor.varintInt() + 1
            patchAt[p] = previous
            patchHigh[p] = cursor.varint()
        }
        return Gaps(floor, bits, at, cursor.at, patchAt, patchHigh)
    }

    fun readGaps(cursor: SegmentReader, rows: Int): Gaps = readColumn(cursor, maxOf(rows - 1, 0))

    fun readFramed(cursor: SegmentReader, n: Int): Gaps = readColumn(cursor, n)

    fun writeIndexes(sink: ByteWriter, values: IntArray, n: Int, bits: Int) {
        var runs = 0
        var runBytes = 0L
        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n && values[j] == values[i]) j++
            runs++
            runBytes += varintBytes(values[i].toLong()) + varintBytes((j - i).toLong())
            i = j
        }
        if (n > 0 && runBytes + 1 < packedBytes(n, bits)) {
            sink.u8(RUNS)
            sink.varint(runs.toLong())
            i = 0
            while (i < n) {
                var j = i + 1
                while (j < n && values[j] == values[i]) j++
                sink.varint(values[i].toLong())
                sink.varint((j - i).toLong())
                i = j
            }
        } else {
            sink.u8(PACKED)
            BitPack.ints(sink, values, n, bits)
        }
    }

    fun readIndexes(cursor: SegmentReader, n: Int, bits: Int): Indexes {
        val mode = cursor.u8()
        if (mode == PACKED) {
            val at = cursor.at
            cursor.at = at + packedBytes(n, bits)
            return Indexes(PACKED, at, bits, IntArray(0), IntArray(0), cursor.at)
        }
        val runs = cursor.varintInt()
        val values = IntArray(runs)
        val lengths = IntArray(runs)
        for (r in 0 until runs) {
            values[r] = cursor.varintInt()
            lengths[r] = cursor.varintInt()
        }
        return Indexes(RUNS, 0, bits, values, lengths, cursor.at)
    }

    private fun varintBytes(v: Long): Int = maxOf(1, (64 - v.countLeadingZeroBits() + 6) / 7)
}
