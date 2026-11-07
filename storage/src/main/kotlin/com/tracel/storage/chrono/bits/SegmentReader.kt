package com.tracel.storage.chrono.bits

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Reads a segment front to back: headers, palettes, anything that is not a packed column.
 *
 * Columns are read in place by [BitUnpack]; this one is for the bytes around them.
 */
class SegmentReader(private val seg: MemorySegment, var at: Long = 0L) {
    fun u8(): Int = seg.get(ValueLayout.JAVA_BYTE, at++).toInt() and 0xFF

    fun u32(): Int = seg.get(LittleEndian.SEG_INT, at).also { at += 4 }

    fun u64(): Long = seg.get(LittleEndian.SEG_LONG, at).also { at += 8 }

    /** Unsigned LEB128, as [ByteWriter.varint] wrote it. */
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = seg.get(ValueLayout.JAVA_BYTE, at++).toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            check(shift < 70) { "a varint at ${at - 1} runs past ten bytes." }
        }
    }

    /** [varint] that has to fit an int. */
    fun varintInt(): Int {
        val v = varint()
        check(v in 0..0xFFFF_FFFFL) { "varint $v does not fit an int." }
        return v.toInt()
    }

    /** A signed number, through [unzigzag]. */
    fun signed(): Long = unzigzag(varint())

    /**
     * Bytes from the segment, advancing the cursor.
     *
     * The caller must not read past the end of the segment.
     */
    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, at, out, 0, n)
        at += n
        return out
    }

    /** Skips [n] bytes. */
    fun skip(n: Long) {
        at += n
    }
}
