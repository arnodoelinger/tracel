package com.tracel.storage.codec.records

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import java.lang.foreign.MemorySegment

/** A container's full slot layout (e.g. a chest, barrel, etc.). */
object ContainerSlot {
    /** Creates a container slot layout. */
    fun layout(epochMillis: Long, slots: List<Triple<Int, Int, Long>>): ByteArray {
        val size = 12 + slots.size * 16
        return recordBytes(size) {
            putI64(0, epochMillis)
            putI32(8, slots.size)
            var at = 12L
            for ((slot, itemKeyId, quantity) in slots) {
                putI32(at, slot)
                putI32(at + 4, itemKeyId)
                putI64(at + 8, quantity)
                at += 16L
            }
        }
    }

    fun layoutEpochMillis(v: MemorySegment): Long = v.i64(0)

    fun layoutCount(v: MemorySegment): Int = v.i32(8)

    fun layoutSlot(v: MemorySegment, index: Int): Int = v.i32(12L + index * 16L)

    fun layoutItemKeyId(v: MemorySegment, index: Int): Int = v.i32(12L + index * 16L + 4L)

    fun layoutQuantity(v: MemorySegment, index: Int): Long = v.i64(12L + index * 16L + 8L)
}
