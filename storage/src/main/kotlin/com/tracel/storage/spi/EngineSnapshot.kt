package com.tracel.storage.spi

import com.tracel.storage.codec.Keys
import java.lang.foreign.MemorySegment

/**
 * A point-in-time view. Cheap to take, must be closed, and pins whatever it needs to stay alive.
 *
 * Every [MemorySegment] it hands out, directly or through a cursor, is read-only and valid until
 * this closes. Copy what has to outlive it.
 */
interface EngineSnapshot : AutoCloseable {
    /**
     * The value at [key], or `null`.
     *
     * The segment is read-only and valid until this closes.
     */
    fun get(key: ByteArray): MemorySegment?

    /**
     * Every entry whose key starts with [prefix], in key order,
     * starting at or after [from].
     *
     * There is deliberately no reverse cursor: every index that wants newest-first stores its
     * sequence inverted instead.
     *
     * @see [Keys]
     */
    fun scan(prefix: ByteArray, from: ByteArray = prefix): EngineCursor = scan(prefix, from, null)

    /** [scan], stopping before [to] when it is not `null`. */
    fun scan(prefix: ByteArray, from: ByteArray, to: ByteArray?): EngineCursor

    /**
     * Up to `parts - 1` keys, ascending, that cut what is under [prefix] from
     * [from] up to [to] into [parts] runs of roughly equal size, so so as many cursors
     * can walk them at once.
     */
    fun splitKeys(prefix: ByteArray, from: ByteArray, to: ByteArray?, parts: Int): List<ByteArray> = emptyList()
}

/**
 * A forward cursor.
 *
 * Not thread-safe; one per reader, one at a time.
 */
interface EngineCursor : AutoCloseable {
    fun next(): Boolean

    fun key(): ByteArray

    fun value(): MemorySegment

    fun keyLength(): Int = key().size

    fun keyByte(at: Int): Byte = key()[at]

    fun keyU32(at: Int): Int {
        val key = key()
        return ((key[at].toInt() and 0xFF) shl 24) or ((key[at + 1].toInt() and 0xFF) shl 16) or
                ((key[at + 2].toInt() and 0xFF) shl 8) or (key[at + 3].toInt() and 0xFF)
    }

    fun keyU64(at: Int): Long {
        val key = key()
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (key[at + i].toLong() and 0xFF)
        return value
    }

    fun skipTo(from: ByteArray)
}
