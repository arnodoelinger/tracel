package com.tracel.storage.codec.records

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import java.lang.foreign.MemorySegment

/** One durability change of one tool lot. */
object Wear {
    /** Creates a wear mark. */
    fun mark(epochMillis: Long, before: Int, after: Int): ByteArray = recordBytes(16) {
        putI64(0, epochMillis)
        putI32(8, before)
        putI32(12, after)
    }

    fun epochMillis(v: MemorySegment): Long = v.i64(0)

    fun before(v: MemorySegment): Int = v.i32(8)

    fun after(v: MemorySegment): Int = v.i32(12)
}
