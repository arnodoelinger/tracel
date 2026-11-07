package com.tracel.storage.ffm

import com.tracel.storage.codec.Keys
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.*
import java.util.zip.CRC32C

/**
 * Unaligned little-endian views over a [MemorySegment]. Little-endian because every machine
 * this runs on is, so a load is a load and not a byte-swap.
 *
 * "Unaligned" is not an oversight: ring slots and record fields are packed at whatever
 * offset the layout puts them at (1, 2, 3 bytes in), and an aligned layout would throw on
 * half of them. We pay a possible unaligned-access penalty instead of padding every field
 * out to its own alignment, which would blow the 16–24 byte record budget.
 *
 * Keys are the exception and are written big-endian — see [Keys].
 */
object Bytes {
    val I8: ValueLayout.OfByte = ValueLayout.JAVA_BYTE
    val I16: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    val I32: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    val I64: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    fun MemorySegment.i8(offset: Long): Byte = get(I8, offset)
    fun MemorySegment.i16(offset: Long): Short = get(I16, offset)
    fun MemorySegment.i32(offset: Long): Int = get(I32, offset)
    fun MemorySegment.i64(offset: Long): Long = get(I64, offset)

    fun MemorySegment.putI8(offset: Long, value: Byte) = set(I8, offset, value)
    fun MemorySegment.putI16(offset: Long, value: Short) = set(I16, offset, value)
    fun MemorySegment.putI32(offset: Long, value: Int) = set(I32, offset, value)
    fun MemorySegment.putI64(offset: Long, value: Long) = set(I64, offset, value)

    /** Reads [length] bytes from this segment starting at [offset] into a new array. */
    fun MemorySegment.readBytes(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        MemorySegment.copy(this, I8, offset, out, 0, length)
        return out
    }

    /**
     * Writes [source] into this segment starting at [offset].
     *
     * The caller must not write past the end of the segment.
     */
    fun MemorySegment.writeBytes(offset: Long, source: ByteArray) {
        MemorySegment.copy(source, 0, this, I8, offset, source.size)
    }

    /** Computes the CRC32C checksum of [bytes]. */
    fun checksum(bytes: ByteArray): Int = CRC32C().apply { update(bytes) }.value.toInt()
}

/** A key, as a byte array. */
class Key(val bytes: ByteArray) : Comparable<Key> {
    val size: Int get() = bytes.size

    fun startsWith(prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && Arrays.equals(bytes, 0, prefix.size, prefix, 0, prefix.size)

    override fun compareTo(other: Key): Int = Arrays.compareUnsigned(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
}
