package com.tracel.storage.ffm

import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import java.lang.Long.compareUnsigned
import java.lang.Long.reverseBytes
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Unsigned lexicographic comparison between a [MemorySegment] range and a `ByteArray`, eight
 * bytes at a time.
 */
object SegmentCompare {
    private val BE_LONG: VarHandle =
        MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)

    /** Compares the bytes in [segment] starting at [offset] with the bytes in [other]. */
    fun compare(segment: MemorySegment, offset: Long, length: Int, other: ByteArray): Int =
        compare(segment, offset, length, other, other.size)

    /**
     * Compares the bytes in [segment] starting at [offset] with the first [otherLength] bytes in
     * [other].
     *
     * @return a negative number if the segment is lexicographically less than the other,
     * a positive number if it is greater, or zero if they are equal.
     */
    fun compare(segment: MemorySegment, offset: Long, length: Int, other: ByteArray, otherLength: Int): Int {
        val shared = if (length < otherLength) length else otherLength
        if (shared >= 8) {
            val last = shared - 8
            var i = 0
            while (i < last) {
                val a = reverseBytes(segment.i64(offset + i))
                val b = BE_LONG.get(other, i) as Long
                if (a != b) return compareUnsigned(a, b)
                i += 8
            }
            val a = reverseBytes(segment.i64(offset + last))
            val b = BE_LONG.get(other, last) as Long
            if (a != b) return compareUnsigned(a, b)
            return length - otherLength
        }
        var i = 0
        while (i < shared) {
            val a = segment.i8(offset + i).toInt() and 0xFF
            val b = other[i].toInt() and 0xFF
            if (a != b) return a - b
            i++
        }
        return length - otherLength
    }

    /**
     * Compares the bytes in [left] starting at [leftOffset] with the bytes in [right] starting at [rightOffset].
     *
     * @return a negative number if the left segment is lexicographically less than the right,
     * a positive number if it is greater, or zero if they are equal.
     */
    fun compare(
        left: MemorySegment,
        leftOffset: Long,
        leftLength: Int,
        right: MemorySegment,
        rightOffset: Long,
        rightLength: Int,
    ): Int {
        val shared = if (leftLength < rightLength) leftLength else rightLength
        if (shared >= 8) {
            val last = shared - 8
            var i = 0
            while (i < last) {
                val a = reverseBytes(left.i64(leftOffset + i))
                val b = reverseBytes(right.i64(rightOffset + i))
                if (a != b) return compareUnsigned(a, b)
                i += 8
            }
            val a = reverseBytes(left.i64(leftOffset + last))
            val b = reverseBytes(right.i64(rightOffset + last))
            if (a != b) return compareUnsigned(a, b)
            return leftLength - rightLength
        }
        var i = 0
        while (i < shared) {
            val a = left.i8(leftOffset + i).toInt() and 0xFF
            val b = right.i8(rightOffset + i).toInt() and 0xFF
            if (a != b) return a - b
            i++
        }
        return leftLength - rightLength
    }

    /**
     * @return true if the bytes in [segment] starting at [offset] with length [length] start with
     * the given [prefix].
     */
    fun startsWith(segment: MemorySegment, offset: Long, length: Int, prefix: ByteArray): Boolean =
        length >= prefix.size && compare(segment, offset, prefix.size, prefix, prefix.size) == 0

    /**
     * @return the first [length] bytes of [key] as a big-endian long, or the whole key if it is shorter than
     * [length].
     */
    fun prefixOf(key: ByteArray, length: Int): Long {
        if (length >= 8) return BE_LONG.get(key, 0) as Long
        var value = 0L
        var i = 0
        while (i < length) {
            value = value or ((key[i].toLong() and 0xFF) shl (56 - (i shl 3)))
            i++
        }
        return value
    }

    /**
     * @return the first 8 bytes of [segment] starting at [offset] as a big-endian long.
     * If the segment has fewer than 8 bytes remaining, the result is undefined.
     */
    fun prefixOf(segment: MemorySegment, offset: Long): Long =
        reverseBytes(segment.i64(offset))
}
