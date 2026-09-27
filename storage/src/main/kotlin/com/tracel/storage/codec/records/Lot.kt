package com.tracel.storage.codec.records

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import java.lang.foreign.MemorySegment

object Lot {
    const val EDGE_SPLIT: Byte = 0
    const val EDGE_TRANSFORM: Byte = 1
    const val EDGE_COMPENSATE: Byte = 2

    const val PACK_HEADER: Long = 12
    const val PACK_ENTRY: Long = 24

    fun lot(itemKeyId: Int, quantity: Long, createdBy: Long): ByteArray =
        recordBytes(20) { putI32(0, itemKeyId); putI64(4, quantity); putI64(12, createdBy) }

    fun lotItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun lotQuantity(v: MemorySegment): Long = v.i64(4)
    fun lotCreatedBy(v: MemorySegment): Long = v.i64(12)

    fun pack(packId: Long, lots: LongArray, remaining: LongArray, fifo: LongArray, count: Int): ByteArray =
        recordBytes((PACK_HEADER + PACK_ENTRY * count).toInt()) {
            putI64(0, packId)
            putI32(8, count)
            for (i in 0 until count) {
                val at = PACK_HEADER + PACK_ENTRY * i
                putI64(at, lots[i])
                putI64(at + 8, remaining[i])
                putI64(at + 16, fifo[i])
            }
        }

    fun packId(v: MemorySegment): Long = v.i64(0)
    fun packCount(v: MemorySegment): Int = v.i32(8)
    fun packLot(v: MemorySegment, i: Int): Long = v.i64(PACK_HEADER + PACK_ENTRY * i)
    fun packRemaining(v: MemorySegment, i: Int): Long = v.i64(PACK_HEADER + PACK_ENTRY * i + 8)
    fun packFifo(v: MemorySegment, i: Int): Long = v.i64(PACK_HEADER + PACK_ENTRY * i + 16)

    fun packLocation(holderId: Int, itemKeyId: Int, tailFifoSeq: Long): ByteArray =
        recordBytes(16) { putI32(0, holderId); putI32(4, itemKeyId); putI64(8, tailFifoSeq) }

    fun locationHolderId(v: MemorySegment): Int = v.i32(0)
    fun locationItemKeyId(v: MemorySegment): Int = v.i32(4)
    fun locationTail(v: MemorySegment): Long = v.i64(8)

    fun packSum(packId: Long, sum: Long): ByteArray = recordBytes(16) { putI64(0, packId); putI64(8, sum) }

    fun sumPackId(v: MemorySegment): Long = v.i64(0)
    fun sumOf(v: MemorySegment): Long = v.i64(8)

    fun edge(kind: Byte, quantity: Long, reference: Long, producedAtHolderId: Int): ByteArray =
        recordBytes(21) { putI8(0, kind); putI64(1, quantity); putI64(9, reference); putI32(17, producedAtHolderId) }

    fun edgeKind(v: MemorySegment): Byte = v.i8(0)
    fun edgeQuantity(v: MemorySegment): Long = v.i64(1)
    fun edgeReference(v: MemorySegment): Long = v.i64(9)
    fun edgeProducedAt(v: MemorySegment): Int = v.i32(17)
}
