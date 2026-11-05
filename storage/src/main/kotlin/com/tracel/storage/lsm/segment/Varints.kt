package com.tracel.storage.lsm.segment

import com.tracel.storage.ffm.Bytes.i8
import java.lang.foreign.MemorySegment

/** Unsigned LEB128. Record headers are three of these: `shared`, `unshared`, `valueLen+1`. */
internal object Varints {
    /** Writes [value] to [dst] starting at [at], returning the index after the last byte written. */
    fun write(dst: ByteArray, at: Int, value: Int): Int {
        require(value >= 0) { "varint is unsigned, got $value." }
        var v = value
        var i = at
        while (v >= 0x80) {
            dst[i++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        dst[i++] = v.toByte()
        return i
    }

    /**
     * Reads a varint from [data] starting at [offset].
     *
     * The result is a packed long. Read the code below.
     */
    fun read(data: MemorySegment, offset: Long): Long {
        var result = 0
        var shift = 0
        var at = offset
        while (true) {
            val b = data.i8(at).toInt() and 0xFF
            at++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            check(shift <= 28) { "varint too long at $offset." }
        }
        return ((at - offset) shl 32) or (result.toLong() and 0xFFFFFFFFL)
    }

    /** [read], from an array. */
    fun read(data: ByteArray, offset: Int): Long {
        var result = 0
        var shift = 0
        var at = offset
        while (true) {
            val b = data[at++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            check(shift <= 28) { "varint too long at $offset." }
        }
        return ((at - offset).toLong() shl 32) or (result.toLong() and 0xFFFFFFFFL)
    }

    /** @return the value of the varint. */
    fun valueOf(packed: Long): Int = packed.toInt()

    /** @return the number of bytes read from the varint. */
    fun sizeOf(packed: Long): Int = (packed ushr 32).toInt()
}
