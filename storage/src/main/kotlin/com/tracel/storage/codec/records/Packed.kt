package com.tracel.storage.codec.records

import com.tracel.platform.Versions
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.storage.ffm.Bytes.writeBytes
import java.lang.foreign.MemorySegment

internal const val CODEC_VERSION: Byte = Versions.Format.RECORD

@PublishedApi
internal val EMPTY_BYTES: ByteArray = ByteArray(0)

internal inline fun recordBytes(size: Int, write: MemorySegment.() -> Unit): ByteArray {
    val bytes = ByteArray(size)
    MemorySegment.ofArray(bytes).write()
    return bytes
}

object Packed {
    const val VERSION: Byte = CODEC_VERSION

    fun long(value: Long): ByteArray = recordBytes(8) { putI64(0, value) }

    fun asLong(v: MemorySegment): Long = v.i64(0)

    fun int(value: Int): ByteArray = recordBytes(4) { putI32(0, value) }

    fun asInt(v: MemorySegment): Int = v.i32(0)

    private const val PACKED: Byte = 0x7F

    fun packed(parts: List<ByteArray>): ByteArray {
        var size = 5
        for (part in parts) size += 4 + part.size
        return recordBytes(size) {
            putI8(0, PACKED)
            putI32(1, parts.size)
            var at = 5L
            for (part in parts) {
                putI32(at, part.size)
                writeBytes(at + 4, part)
                at += 4L + part.size
            }
        }
    }

    fun forEachPacked(v: MemorySegment, action: (MemorySegment) -> Unit) {
        if (v.byteSize() < 5 || v.i8(0) != PACKED) {
            action(v)
            return
        }
        val count = v.i32(1)
        var at = 5L
        repeat(count) {
            val length = v.i32(at)
            action(v.asSlice(at + 4, length.toLong()))
            at += 4L + length
        }
    }
}
