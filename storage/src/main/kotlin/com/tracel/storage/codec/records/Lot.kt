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

    fun lot(itemKeyId: Int, quantity: Long, createdBy: Long): ByteArray =
        recordBytes(20) { putI32(0, itemKeyId); putI64(4, quantity); putI64(12, createdBy) }

    fun lotItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun lotQuantity(v: MemorySegment): Long = v.i64(4)
    fun lotCreatedBy(v: MemorySegment): Long = v.i64(12)

    fun placement(lotId: Long, remaining: Long): ByteArray =
        recordBytes(16) { putI64(0, lotId); putI64(8, remaining) }

    fun placementLotId(v: MemorySegment): Long = v.i64(0)
    fun placementRemaining(v: MemorySegment): Long = v.i64(8)

    fun placementRev(itemKeyId: Int, fifoSeq: Long): ByteArray =
        recordBytes(12) { putI32(0, itemKeyId); putI64(4, fifoSeq) }

    fun placementRevItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun placementRevFifoSeq(v: MemorySegment): Long = v.i64(4)

    fun edge(kind: Byte, quantity: Long, reference: Long, producedAtHolderId: Int): ByteArray =
        recordBytes(21) { putI8(0, kind); putI64(1, quantity); putI64(9, reference); putI32(17, producedAtHolderId) }

    fun edgeKind(v: MemorySegment): Byte = v.i8(0)
    fun edgeQuantity(v: MemorySegment): Long = v.i64(1)
    fun edgeReference(v: MemorySegment): Long = v.i64(9)
    fun edgeProducedAt(v: MemorySegment): Int = v.i32(17)
}
