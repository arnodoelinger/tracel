package com.tracel.storage.codec

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.LotContribution
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.storage.ffm.Bytes.i16
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI16
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import java.lang.foreign.MemorySegment
import java.util.UUID

/**
 * Every value shape the store holds, packed little-endian at fixed offsets.
 *
 * Decoding reads straight out of a [MemorySegment] — an `mmap`ed segment file or a memtable
 * arena.
 */
object Records {
    /** Bumped whenever a layout below changes shape. Written into every transaction record. */
    const val VERSION: Byte = 1

    // Box "Lot": 20 bytes

    fun lot(itemKeyId: Int, quantity: Long, createdBy: Long): ByteArray =
        value(20) { putI32(0, itemKeyId); putI64(4, quantity); putI64(12, createdBy) }

    fun lotItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun lotQuantity(v: MemorySegment): Long = v.i64(4)
    fun lotCreatedBy(v: MemorySegment): Long = v.i64(12)

    // Box "Placement": 16 bytes

    fun placement(lotId: Long, remaining: Long): ByteArray =
        value(16) { putI64(0, lotId); putI64(8, remaining) }

    fun placementLotId(v: MemorySegment): Long = v.i64(0)
    fun placementRemaining(v: MemorySegment): Long = v.i64(8)

    // Box "Reverse placement": 12 bytes

    fun placementRev(itemKeyId: Int, fifoSeq: Long): ByteArray =
        value(12) { putI32(0, itemKeyId); putI64(4, fifoSeq) }

    fun placementRevItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun placementRevFifoSeq(v: MemorySegment): Long = v.i64(4)

    // Box "Plain counters and totals": 8 bytes

    fun long(value: Long): ByteArray = value(8) { putI64(0, value) }

    fun asLong(v: MemorySegment): Long = v.i64(0)

    fun int(value: Int): ByteArray = value(4) { putI32(0, value) }

    fun asInt(v: MemorySegment): Int = v.i32(0)

    // Box "Lot edge": 21 bytes

    const val EDGE_SPLIT: Byte = 0
    const val EDGE_TRANSFORM: Byte = 1
    const val EDGE_COMPENSATE: Byte = 2

    fun edge(kind: Byte, quantity: Long, reference: Long, producedAtHolderId: Int): ByteArray =
        value(21) { putI8(0, kind); putI64(1, quantity); putI64(9, reference); putI32(17, producedAtHolderId) }

    fun edgeKind(v: MemorySegment): Byte = v.i8(0)
    fun edgeQuantity(v: MemorySegment): Long = v.i64(1)
    fun edgeReference(v: MemorySegment): Long = v.i64(9)
    fun edgeProducedAt(v: MemorySegment): Int = v.i32(17)

    // Box "Lease": 16 bytes

    fun lease(jobId: Long, acquiredAtMillis: Long): ByteArray =
        value(16) { putI64(0, jobId); putI64(8, acquiredAtMillis) }

    fun leaseJobId(v: MemorySegment): Long = v.i64(0)
    fun leaseAcquiredAt(v: MemorySegment): Long = v.i64(8)

    // Box "Pending delivery": 28 bytes

    fun pending(itemKeyId: Int, delta: Long, jobId: Long, createdMillis: Long): ByteArray =
        value(28) { putI32(0, itemKeyId); putI64(4, delta); putI64(12, jobId); putI64(20, createdMillis) }

    fun pendingItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun pendingDelta(v: MemorySegment): Long = v.i64(4)
    fun pendingJobId(v: MemorySegment): Long = v.i64(12)

    // Box "Rollback job header": 8 bytes

    fun rbJob(restoreToHolderId: Int, stepCount: Int): ByteArray =
        value(8) { putI32(0, restoreToHolderId); putI32(4, stepCount) }

    fun rbJobRestoreTo(v: MemorySegment): Int = v.i32(0)
    fun rbJobStepCount(v: MemorySegment): Int = v.i32(4)

    // Box "Rollback plan fragment": variable

    private const val STEP_TAKE: Byte = 0
    private const val STEP_MINT: Byte = 1
    private const val STEP_DEBT: Byte = 2
    private const val STEP_UNMAKE: Byte = 3

    fun step(step: RollbackStep, holderId: (HolderId) -> Int): ByteArray = when (step) {
        is RollbackStep.Take -> value(21) {
            putI8(0, STEP_TAKE); putI64(1, step.lotId.raw); putI64(9, step.quantity.raw)
            putI32(17, holderId(step.holder))
        }

        is RollbackStep.Mint -> value(18) {
            putI8(0, STEP_MINT); putI64(1, step.lotId.raw); putI64(9, step.quantity.raw)
            putI8(17, step.reason.ordinal.toByte())
        }

        is RollbackStep.Debt -> value(33) {
            putI8(0, STEP_DEBT); putI64(1, step.lotId.raw); putI64(9, step.quantity.raw)
            putI64(17, step.player.mostSignificantBits); putI64(25, step.player.leastSignificantBits)
        }

        is RollbackStep.Unmake -> value(23 + step.inputs.size * 16) {
            putI8(0, STEP_UNMAKE); putI64(1, step.outputLot.raw); putI64(9, step.craftedBy.raw)
            putI32(17, holderId(step.holder)); putI16(21, step.inputs.size.toShort())
            step.inputs.forEachIndexed { i, input ->
                putI64(23L + i * 16, input.lotId.raw)
                putI64(31L + i * 16, input.quantity.raw)
            }
        }
    }

    fun decodeStep(v: MemorySegment, holder: (Int) -> HolderId): RollbackStep =
        when (val kind = v.i8(0)) {
            STEP_TAKE -> RollbackStep.Take(LotId(v.i64(1)), Quantity(v.i64(9)), holder(v.i32(17)))
            STEP_MINT -> RollbackStep.Mint(LotId(v.i64(1)), Quantity(v.i64(9)), SinkKind.entries[v.i8(17).toInt()])
            STEP_DEBT -> RollbackStep.Debt(LotId(v.i64(1)), Quantity(v.i64(9)), UUID(v.i64(17), v.i64(25)))
            STEP_UNMAKE -> {
                val count = v.i16(21).toInt() and 0xFFFF
                val inputs = ArrayList<LotContribution>(count)
                for (i in 0 until count) {
                    inputs += LotContribution(LotId(v.i64(23L + i * 16)), Quantity(v.i64(31L + i * 16)))
                }
                RollbackStep.Unmake(
                    LotId(v.i64(1)),
                    inputs,
                    TxnId(v.i64(9)),
                    holder(v.i32(17)),
                )
            }

            else -> error("unrecognized rollback step kind: $kind")
        }

    // Box "Transaction": 24-byte header + 24 bytes per flow

    const val TXN_HEADER_BYTES = 24
    const val FLOW_BYTES = 24

    fun transactionSize(flowCount: Int): Int = TXN_HEADER_BYTES + flowCount * FLOW_BYTES

    fun writeTransactionHeader(
        into: MemorySegment,
        cause: CauseKind,
        flowCount: Int,
        causedByHolderId: Int,
        txnId: Long,
        epochMillis: Long,
    ) {
        into.putI8(0, VERSION)
        into.putI8(1, cause.ordinal.toByte())
        into.putI16(2, flowCount.toShort())
        into.putI32(4, causedByHolderId)
        into.putI64(8, txnId)
        into.putI64(16, epochMillis)
    }

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
    fun flowKind(v: MemorySegment, i: Int): FlowKind = FlowKind.entries[v.i8((TXN_HEADER_BYTES + i * FLOW_BYTES + 12).toLong()).toInt()]

    fun flowQuantity(v: MemorySegment, i: Int): Long = v.i64((TXN_HEADER_BYTES + i * FLOW_BYTES + 16).toLong())

    private inline fun value(size: Int, write: MemorySegment.() -> Unit): ByteArray {
        val bytes = ByteArray(size)
        MemorySegment.ofArray(bytes).write()
        return bytes
    }
}
