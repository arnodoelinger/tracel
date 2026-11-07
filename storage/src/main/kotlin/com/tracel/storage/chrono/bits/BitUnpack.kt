package com.tracel.storage.chrono.bits

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Reads what [BitPack] wrote, straight out of a segment. */
object BitUnpack {
    /** Bytes a reader may overrun past the end of a run. */
    const val PADDING: Int = 16

    /** Value [i] of the run of [bits]-wide values that starts at byte [at]. */
    fun get(seg: MemorySegment, at: Long, bits: Int, i: Int): Long {
        if (bits == 0) return 0L
        val bit = i.toLong() * bits
        val byte = at + (bit ushr 3)
        val shift = (bit and 7).toInt()
        val word = seg.get(LittleEndian.SEG_LONG, byte) ushr shift
        if (bits + shift <= 64) return word and lowMask(bits)
        val high = seg.get(LittleEndian.SEG_LONG, byte + 8) shl (64 - shift)
        return (word or high) and lowMask(bits)
    }

    /** [get] of a value no wider than an int. */
    fun int(seg: MemorySegment, at: Long, bits: Int, i: Int): Int {
        if (bits == 0) return 0
        val bit = i.toLong() * bits
        return ((seg.get(LittleEndian.SEG_LONG, at + (bit ushr 3)) ushr (bit and 7).toInt()) and lowMask(bits)).toInt()
    }

    /** The first [n] values, each at most 32 bits, into [out] from [into]. */
    fun ints(seg: MemorySegment, at: Long, bits: Int, n: Int, out: IntArray, into: Int = 0) {
        when (bits) {
            0 -> out.fill(0, into, into + n)
            8 -> for (i in 0 until n) out[into + i] = seg.get(ValueLayout.JAVA_BYTE, at + i).toInt() and 0xFF
            16 -> for (i in 0 until n) out[into + i] = seg.get(LittleEndian.SEG_SHORT, at + i * 2L).toInt() and 0xFFFF
            32 -> for (i in 0 until n) out[into + i] = seg.get(LittleEndian.SEG_INT, at + i * 4L)
            else -> {
                val mask = lowMask(bits)
                var bit = 0L
                for (i in 0 until n) {
                    out[into + i] = ((seg.get(LittleEndian.SEG_LONG, at + (bit ushr 3)) ushr (bit and 7).toInt()) and mask).toInt()
                    bit += bits
                }
            }
        }
    }

    /**
     * [ints] with every value looked up in [palette] on the way out:
     * a palette-coded column decodes in one pass, and the indexes never exist as
     * an array of their own.
     */
    fun mapped(seg: MemorySegment, at: Long, bits: Int, n: Int, palette: IntArray, out: IntArray, into: Int = 0) {
        if (bits == 0) {
            out.fill(if (palette.isEmpty()) 0 else palette[0], into, into + n)
            return
        }
        val mask = lowMask(bits)
        var bit = 0L
        for (i in 0 until n) {
            out[into + i] = palette[((seg.get(LittleEndian.SEG_LONG, at + (bit ushr 3)) ushr (bit and 7).toInt()) and mask).toInt()]
            bit += bits
        }
    }

    /**
     * Rebuilds a column stored as framed deltas: `out[into + i] = out[into + i - 1] + floor + value i`, starting
     * from [start].
     *
     * Unpacking and the prefix sum are one loop, so the deltas are never written down.
     */
    fun deltas(seg: MemorySegment, at: Long, bits: Int, n: Int, start: Long, floor: Long, out: LongArray, into: Int = 0) {
        var acc = start
        if (bits == 0) {
            for (i in 0 until n) {
                acc += floor
                out[into + i] = acc
            }
            return
        }
        if (bits > 57) {
            for (i in 0 until n) {
                acc += floor + get(seg, at, bits, i)
                out[into + i] = acc
            }
            return
        }
        val mask = lowMask(bits)
        var bit = 0L
        for (i in 0 until n) {
            acc += floor + ((seg.get(LittleEndian.SEG_LONG, at + (bit ushr 3)) ushr (bit and 7).toInt()) and mask)
            out[into + i] = acc
            bit += bits
        }
    }

    /**
     * [deltas] whose deltas went through [zigzag] before they were framed:
     * a column that mostly climbs but may step back now and then, like wall-clock time.
     */
    fun zigzagDeltas(seg: MemorySegment, at: Long, bits: Int, n: Int, start: Long, floor: Long, out: LongArray, into: Int = 0) {
        var acc = start
        for (i in 0 until n) {
            acc += unzigzag(floor + get(seg, at, bits, i))
            out[into + i] = acc
        }
    }

    /**
     * Keeps the indexes of the first [n] values whose bit is set in [admitted], into [selection],
     * and says how many.
     *
     * Also, it's branch-free.
     */
    fun select(seg: MemorySegment, at: Long, bits: Int, n: Int, admitted: LongArray, selection: IntArray): Int {
        var kept = 0
        if (bits == 0) {
            if (admitted[0] and 1L == 0L) return 0
            for (i in 0 until n) selection[i] = i
            return n
        }
        val mask = lowMask(bits)
        var bit = 0L
        for (i in 0 until n) {
            val v = ((seg.get(LittleEndian.SEG_LONG, at + (bit ushr 3)) ushr (bit and 7).toInt()) and mask).toInt()
            selection[kept] = i
            kept += ((admitted[v ushr 6] ushr v) and 1L).toInt()
            bit += bits
        }
        return kept
    }

    /** Narrows the first [kept] indexes of [selection] to those whose value has its bit set in [admitted]. */
    fun refine(seg: MemorySegment, at: Long, bits: Int, selection: IntArray, kept: Int, admitted: LongArray): Int {
        if (bits == 0) return if (admitted[0] and 1L == 0L) 0 else kept
        var out = 0
        for (j in 0 until kept) {
            val i = selection[j]
            val v = int(seg, at, bits, i)
            selection[out] = i
            out += ((admitted[v ushr 6] ushr v) and 1L).toInt()
        }
        return out
    }
}
