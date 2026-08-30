package com.tracel.storage.ffm

import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Unsigned lexicographic comparison between a [MemorySegment] range and a `ByteArray`, eight
 * bytes at a time.
 *
 * Reading eight little-endian bytes and byte-swapping gives the big-endian numeric value of
 * that run, and unsigned comparison of those two longs *is* lexicographic comparison of the
 * bytes. Keys here are 9–25 bytes, so this is two or three comparisons instead of twenty-five.
 */
object SegmentCompare {
    private val BE_LONG: VarHandle =
        MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)

    fun compare(segment: MemorySegment, offset: Long, length: Int, other: ByteArray): Int =
        compare(segment, offset, length, other, other.size)

    fun compare(segment: MemorySegment, offset: Long, length: Int, other: ByteArray, otherLength: Int): Int {
        val shared = if (length < otherLength) length else otherLength
        if (shared >= 8) {
            val last = shared - 8
            var i = 0
            while (i < last) {
                val a = java.lang.Long.reverseBytes(segment.i64(offset + i))
                val b = BE_LONG.get(other, i) as Long
                if (a != b) return java.lang.Long.compareUnsigned(a, b)
                i += 8
            }
            val a = java.lang.Long.reverseBytes(segment.i64(offset + last))
            val b = BE_LONG.get(other, last) as Long
            if (a != b) return java.lang.Long.compareUnsigned(a, b)
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
                val a = java.lang.Long.reverseBytes(left.i64(leftOffset + i))
                val b = java.lang.Long.reverseBytes(right.i64(rightOffset + i))
                if (a != b) return java.lang.Long.compareUnsigned(a, b)
                i += 8
            }
            val a = java.lang.Long.reverseBytes(left.i64(leftOffset + last))
            val b = java.lang.Long.reverseBytes(right.i64(rightOffset + last))
            if (a != b) return java.lang.Long.compareUnsigned(a, b)
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

    fun startsWith(segment: MemorySegment, offset: Long, length: Int, prefix: ByteArray): Boolean =
        length >= prefix.size && compare(segment, offset, prefix.size, prefix, prefix.size) == 0

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

    fun prefixOf(segment: MemorySegment, offset: Long): Long =
        java.lang.Long.reverseBytes(segment.i64(offset))
}
