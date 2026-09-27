package com.tracel.storage.codec.records

import com.tracel.annotations.CauseKind
import com.tracel.model.flow.FlowKind
import com.tracel.storage.ffm.Bytes.i16
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI16
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import java.lang.foreign.MemorySegment

object Txn {
    const val TXN_HEADER_BYTES = 40
    const val FLOW_BYTES = 24

    fun transactionSize(flowCount: Int): Int = TXN_HEADER_BYTES + flowCount * FLOW_BYTES

    fun writeTransactionHeader(
        into: MemorySegment,
        cause: CauseKind,
        flowCount: Int,
        causedByHolderId: Int,
        txnId: Long,
        epochMillis: Long,
        worldId: Int = 0,
        x: Int = 0,
        y: Int = 0,
        z: Int = 0,
    ) {
        into.putI8(0, CODEC_VERSION)
        into.putI8(1, cause.ordinal.toByte())
        into.putI16(2, flowCount.toShort())
        into.putI32(4, causedByHolderId)
        into.putI64(8, txnId)
        into.putI64(16, epochMillis)
        into.putI32(24, worldId)
        into.putI32(28, x)
        into.putI32(32, y)
        into.putI32(36, z)
    }

    fun txnWorldId(v: MemorySegment): Int = v.i32(24)
    fun txnX(v: MemorySegment): Int = v.i32(28)
    fun txnY(v: MemorySegment): Int = v.i32(32)
    fun txnZ(v: MemorySegment): Int = v.i32(36)

    fun writeFlow(
        into: MemorySegment,
        index: Int,
        itemKeyId: Int,
        sourceHolderId: Int,
        destinationHolderId: Int,
        kind: FlowKind,
        quantity: Long,
    ) {
        val at = (TXN_HEADER_BYTES + index * FLOW_BYTES).toLong()
        into.putI32(at, itemKeyId)
        into.putI32(at + 4, sourceHolderId)
        into.putI32(at + 8, destinationHolderId)
        into.putI8(at + 12, kind.ordinal.toByte())
        into.putI8(at + 13, 0)
        into.putI16(at + 14, 0)
        into.putI64(at + 16, quantity)
    }

    fun txnVersion(v: MemorySegment): Byte = v.i8(0)
    fun txnCause(v: MemorySegment): CauseKind = CauseKind.entries[v.i8(1).toInt()]
    fun txnFlowCount(v: MemorySegment): Int = v.i16(2).toInt() and 0xFFFF
    fun txnCausedBy(v: MemorySegment): Int = v.i32(4)
    fun txnId(v: MemorySegment): Long = v.i64(8)
    fun txnEpochMillis(v: MemorySegment): Long = v.i64(16)

    fun flowItemKeyId(v: MemorySegment, i: Int): Int = v.i32((TXN_HEADER_BYTES + i * FLOW_BYTES).toLong())
    fun flowSource(v: MemorySegment, i: Int): Int = v.i32((TXN_HEADER_BYTES + i * FLOW_BYTES + 4).toLong())
    fun flowDestination(v: MemorySegment, i: Int): Int = v.i32((TXN_HEADER_BYTES + i * FLOW_BYTES + 8).toLong())
    fun flowKind(v: MemorySegment, i: Int): FlowKind =
        FlowKind.entries[v.i8((TXN_HEADER_BYTES + i * FLOW_BYTES + 12).toLong()).toInt()]

    fun flowQuantity(v: MemorySegment, i: Int): Long = v.i64((TXN_HEADER_BYTES + i * FLOW_BYTES + 16).toLong())
}
