package com.tracel.storage.codec

import com.tracel.storage.ffm.Bytes.i16
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI16
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.Bytes.writeBytes
import java.lang.foreign.MemorySegment
import java.util.*

/** A little-endian layout (records). */
@Suppress("SameParameterValue")
abstract class Record(head: Record? = null) {
    /** Bytes of the fixed part. */
    var size: Long = head?.size ?: 0L; private set

    internal val blobs = ArrayList<Blob>()

    private fun <T> field(bytes: Int, make: (Long) -> T): T = make(size).also { size += bytes }

    protected fun i8() = field(1, ::I8)
    protected fun u16() = field(2, ::U16)
    protected fun i32() = field(4, ::I32)
    protected fun i64() = field(8, ::I64)
    protected fun f32() = field(4, ::F32)
    protected fun f64() = field(8, ::F64)
    protected fun flag() = field(1, ::Flag)
    protected fun uuid() = field(16, ::UuidAt)
    protected fun pad(bytes: Int) = field(bytes) { Pad(it, bytes) }
    protected fun <E : Enum<E>> enum(entries: List<E>) = field(1) { EnumAt(it, entries) }
    protected fun blob16() = field(2) { Blob(this, it, blobs.size) }.also { blobs += it }

    /** Fields that repeat, one set per row, after everything declared before. */
    protected fun rows() = Rows(size)

    /** The fixed part plus [tails]. */
    fun sized(vararg tails: ByteArray): Int = (size + tails.sumOf { it.size }).toInt()

    inline fun build(bytes: Int = size.toInt(), fill: Writer.() -> Unit): ByteArray {
        val out = ByteArray(bytes)
        Writer(MemorySegment.ofArray(out), 0L).fill()
        return out
    }

    /** [fill] into a segment someone else sized, this record starting at [base]. */
    inline fun write(into: MemorySegment, base: Long = 0L, fill: Writer.() -> Unit) = Writer(into, base).fill()
}

class Rows internal constructor(internal val base: Long) {
    internal var stride = 0L; private set

    private fun <T> field(bytes: Int, make: (Long) -> T): T = make(stride).also { stride += bytes }

    fun i32() = field(4) { RowI32(this, it) }
    fun i64() = field(8) { RowI64(this, it) }
    fun pad(bytes: Int) = field(bytes) { RowPad(this, it, bytes) }
    fun <E : Enum<E>> enum(entries: List<E>) = field(1) { RowEnum(this, it, entries) }

    /** Bytes of a record with [count] rows. */
    fun size(count: Int): Int = (base + stride * count).toInt()

    internal fun at(i: Int, off: Long): Long = base + stride * i + off
}

class I8 internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment): Byte = v.i8(at)
    operator fun get(v: MemorySegment, base: Long): Byte = v.i8(base + at)
    operator fun set(v: MemorySegment, base: Long, x: Byte) = v.putI8(base + at, x)
}

class U16 internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment): Int = v.i16(at).toInt() and 0xFFFF
    operator fun get(v: MemorySegment, base: Long): Int = v.i16(base + at).toInt() and 0xFFFF
    operator fun set(v: MemorySegment, base: Long, x: Int) = v.putI16(base + at, x.toShort())
}

class I32 internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment): Int = v.i32(at)
    operator fun get(v: MemorySegment, base: Long): Int = v.i32(base + at)
    operator fun set(v: MemorySegment, base: Long, x: Int) = v.putI32(base + at, x)
}

class I64 internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment): Long = v.i64(at)
    operator fun get(v: MemorySegment, base: Long): Long = v.i64(base + at)
    operator fun set(v: MemorySegment, base: Long, x: Long) = v.putI64(base + at, x)
}

class F32 internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment, base: Long = 0L): Float = Float.fromBits(v.i32(base + at))
}

class F64 internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment, base: Long = 0L): Double = Double.fromBits(v.i64(base + at))
}

class Flag internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment): Boolean = v.i8(at) != 0.toByte()
}

class UuidAt internal constructor(internal val at: Long) {
    operator fun get(v: MemorySegment, base: Long = 0L): UUID = UUID(v.i64(base + at), v.i64(base + at + 8))
    fun high(v: MemorySegment): Long = v.i64(at)
    fun low(v: MemorySegment): Long = v.i64(at + 8)
}

class Pad internal constructor(internal val at: Long, internal val bytes: Int) {
    fun clear(v: MemorySegment, base: Long) = zero(v, base + at, bytes)
}

class EnumAt<E : Enum<E>> internal constructor(internal val at: Long, private val entries: List<E>) {
    operator fun get(v: MemorySegment): E = entries[v.i8(at).toInt() and 0xFF]
    operator fun get(v: MemorySegment, base: Long): E = entries[v.i8(base + at).toInt() and 0xFF]
}

/** A `u16` length among the fixed fields; the bytes come after the fixed part, behind every earlier blob. */
class Blob internal constructor(private val record: Record, internal val lengthAt: Long, private val index: Int) {
    fun length(v: MemorySegment, base: Long = 0L): Int = v.i16(base + lengthAt).toInt() and 0xFFFF

    fun offset(v: MemorySegment, base: Long = 0L): Long {
        var at = base + record.size
        for (i in 0 until index) at += record.blobs[i].length(v, base)
        return at
    }

    operator fun get(v: MemorySegment, base: Long = 0L): ByteArray = v.readBytes(offset(v, base), length(v, base))

    fun slice(v: MemorySegment, base: Long = 0L): MemorySegment =
        v.asSlice(offset(v, base), length(v, base).toLong())

    /** Where the record behind this one starts, when this is the last blob. */
    fun end(v: MemorySegment, base: Long = 0L): Long = offset(v, base) + length(v, base)
}

class RowI32 internal constructor(private val rows: Rows, private val off: Long) {
    internal fun at(i: Int): Long = rows.at(i, off)
    operator fun get(v: MemorySegment, i: Int): Int = v.i32(at(i))
}

class RowI64 internal constructor(private val rows: Rows, private val off: Long) {
    internal fun at(i: Int): Long = rows.at(i, off)
    operator fun get(v: MemorySegment, i: Int): Long = v.i64(at(i))
}

class RowPad internal constructor(private val rows: Rows, private val off: Long, internal val bytes: Int) {
    internal fun at(i: Int): Long = rows.at(i, off)
}

class RowEnum<E : Enum<E>> internal constructor(private val rows: Rows, private val off: Long, private val entries: List<E>) {
    internal fun at(i: Int): Long = rows.at(i, off)
    operator fun get(v: MemorySegment, i: Int): E = entries[v.i8(at(i)).toInt() and 0xFF]
}

/** Writes fields: `epochMillis(millis)`. Blobs go in declaration order, each after the one before. */
class Writer(val v: MemorySegment, val base: Long) {
    operator fun I8.invoke(x: Byte) = v.putI8(base + at, x)
    operator fun U16.invoke(x: Int) = v.putI16(base + at, x.toShort())
    operator fun I32.invoke(x: Int) = v.putI32(base + at, x)
    operator fun I64.invoke(x: Long) = v.putI64(base + at, x)
    operator fun F32.invoke(x: Float) = v.putI32(base + at, x.toRawBits())
    operator fun F64.invoke(x: Double) = v.putI64(base + at, x.toRawBits())
    operator fun Flag.invoke(x: Boolean) = v.putI8(base + at, if (x) 1 else 0)
    operator fun Pad.invoke() = zero(v, base + at, bytes)
    operator fun <E : Enum<E>> EnumAt<E>.invoke(x: E) = v.putI8(base + at, x.ordinal.toByte())

    operator fun UuidAt.invoke(x: UUID) {
        v.putI64(base + at, x.mostSignificantBits)
        v.putI64(base + at + 8, x.leastSignificantBits)
    }

    operator fun Blob.invoke(x: ByteArray) {
        v.putI16(base + lengthAt, x.size.toShort())
        v.writeBytes(offset(v, base), x)
    }

    operator fun RowI32.invoke(i: Int, x: Int) = v.putI32(at(i), x)
    operator fun RowI64.invoke(i: Int, x: Long) = v.putI64(at(i), x)
    operator fun RowPad.invoke(i: Int) = zero(v, at(i), bytes)
    operator fun <E : Enum<E>> RowEnum<E>.invoke(i: Int, x: E) = v.putI8(at(i), x.ordinal.toByte())
}

/** Plain stores, no slice: gaps sit on the capture path, which allocates nothing. */
internal fun zero(v: MemorySegment, at: Long, bytes: Int) {
    var i = 0
    while (i + 8 <= bytes) v.putI64(at + i, 0L).also { i += 8 }
    while (i + 2 <= bytes) v.putI16(at + i, 0).also { i += 2 }
    if (i < bytes) v.putI8(at + i, 0)
}
