package com.tracel.storage.ffm

import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import java.lang.foreign.MemorySegment
import kotlin.math.min

/**
 * Unsigned lexicographic comparison between a [MemorySegment] range and a `ByteArray`, eight
 * bytes at a time.
 *
 * Reading eight little-endian bytes and byte-swapping gives the big-endian numeric value of
 * that run, and unsigned comparison of those two longs *is* lexicographic comparison of the
 * bytes. Keys here are 9–25 bytes, so this is two or three comparisons instead of twenty-five.
 */
object SegmentCompare {
    fun compare(segment: MemorySegment, offset: Long, length: Int, other: ByteArray): Int {
        val shared = min(length, other.size)
        var i = 0
        while (i + 8 <= shared) {
            val a = java.lang.Long.reverseBytes(segment.i64(offset + i))
            val b = longAt(other, i)
            if (a != b) return java.lang.Long.compareUnsigned(a, b)
            i += 8
        }
        while (i < shared) {
            val a = segment.i8(offset + i).toInt() and 0xFF
            val b = other[i].toInt() and 0xFF
            if (a != b) return a - b
            i++
        }
        return length - other.size
    }

    /** Same ordering, both sides in a segment. Used by the segment-file merge in compaction. */
    fun compare(
        left: MemorySegment,
        leftOffset: Long,
        leftLength: Int,
        right: MemorySegment,
        rightOffset: Long,
        rightLength: Int,
    ): Int {
        val shared = min(leftLength, rightLength)
        var i = 0
        while (i + 8 <= shared) {
            val a = java.lang.Long.reverseBytes(left.i64(leftOffset + i))
            val b = java.lang.Long.reverseBytes(right.i64(rightOffset + i))
            if (a != b) return java.lang.Long.compareUnsigned(a, b)
            i += 8
        }
        while (i < shared) {
            val a = left.i8(leftOffset + i).toInt() and 0xFF
            val b = right.i8(rightOffset + i).toInt() and 0xFF
            if (a != b) return a - b
            i++
        }
        return leftLength - rightLength
    }

    /** True when the segment range at [offset] begins with [prefix]. */
    fun startsWith(segment: MemorySegment, offset: Long, length: Int, prefix: ByteArray): Boolean {
        if (length < prefix.size) return false
        return compare(segment, offset, prefix.size, prefix) == 0
    }

    private fun longAt(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }
}
