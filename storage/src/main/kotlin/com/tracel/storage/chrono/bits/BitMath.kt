package com.tracel.storage.chrono.bits

import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Bits it takes to write [v] as an unsigned number.
 *
 * Zero takes none, and is stored in none.
 */
fun widthOf(v: Long): Int = 64 - v.countLeadingZeroBits()

/** [widthOf] for an int, read as unsigned. */
fun widthOf(v: Int): Int = 32 - v.countLeadingZeroBits()

/** Bits an index into [size] distinct things takes. */
fun indexWidth(size: Int): Int = if (size <= 1) 0 else widthOf(size - 1)

/** Folds the sign into the low bit, so small negatives stay small: 0, -1, 1, -2 become 0, 1, 2, 3. */
fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)

/** Undoes [zigzag]. */
fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1L)

/** The low [bits] bits set. */
fun lowMask(bits: Int): Long = if (bits >= 64) -1L else (1L shl bits) - 1L

/** Bytes that [n] values of [bits] each occupy once packed back to back. */
fun packedBytes(n: Int, bits: Int): Int = ((n.toLong() * bits + 7) ushr 3).toInt()

/** Little-endian views, unaligned: the only byte order anything in here speaks. */
internal object LittleEndian {
    val SHORT: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
    val INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
    val LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

    val SEG_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    val SEG_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    val SEG_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
}
