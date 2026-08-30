package com.tracel.storage.codec

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.plan.LotContribution
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.BlockExtras
import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.model.world.EntityShape
import com.tracel.model.world.EntityTypeKey
import com.tracel.model.world.EntityExtras
import com.tracel.model.world.LogKind
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
import java.util.UUID

/**
 * Every value shape the store holds, packed little-endian at fixed offsets.
 *
 * Decoding reads straight out of a [MemorySegment] — an `mmap`ed segment file or a memtable
 * arena.
 */
object Records {
    /** Bumped whenever a layout below changes shape. Written into every transaction record. */
    const val VERSION: Byte = 2 // TODO: remove it

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

    // Box "Shared index value": 1 byte, or 9 when the spatial index carries a timestamp

    fun logKind(kind: LogKind): ByteArray = value(1) { putI8(0, kind.ordinal.toByte()) }

    fun logKind(kind: LogKind, epochMillis: Long, cause: CauseKind, x: Int, y: Int, z: Int): ByteArray =
        value(22) {
            putI8(0, kind.ordinal.toByte())
            putI64(1, epochMillis)
            putI8(9, cause.ordinal.toByte())
            putI32(10, x)
            putI32(14, y)
            putI32(18, z)
        }

    fun asLogKind(v: MemorySegment): LogKind = LogKind.entries[v.i8(0).toInt()]

    fun logKindMillis(v: MemorySegment): Long? = if (v.byteSize() >= 9) v.i64(1) else null

    fun logKindCause(v: MemorySegment): CauseKind? =
        if (v.byteSize() >= 10) CauseKind.entries[v.i8(9).toInt() and 0xFF] else null

    fun logKindX(v: MemorySegment): Int? = if (v.byteSize() >= 22) v.i32(10) else null
    fun logKindY(v: MemorySegment): Int? = if (v.byteSize() >= 22) v.i32(14) else null
    fun logKindZ(v: MemorySegment): Int? = if (v.byteSize() >= 22) v.i32(18) else null

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

    // Box "Rollback job header": 16 bytes

    fun rbJob(restoreToHolderId: Int, stepCount: Int, createCount: Int, destroyCount: Int): ByteArray =
        value(16) { putI32(0, restoreToHolderId); putI32(4, stepCount); putI32(8, createCount); putI32(12, destroyCount) }

    fun rbJobRestoreTo(v: MemorySegment): Int = v.i32(0)
    fun rbJobStepCount(v: MemorySegment): Int = v.i32(4)
    fun rbJobCreateCount(v: MemorySegment): Int = v.i32(8)
    fun rbJobDestroyCount(v: MemorySegment): Int = v.i32(12)

    // Box "Structure step": variable

    private const val STRUCT_SET_BLOCK: Byte = 0
    private const val STRUCT_SPAWN_ENTITY: Byte = 1
    private const val STRUCT_REMOVE_ENTITY: Byte = 2

    fun structureStep(
        step: StructureStep,
        worldId: (WorldId) -> Int,
        blockDataId: (BlockDataKey) -> Int,
        entityTypeId: (EntityTypeKey) -> Int,
    ): ByteArray = when (step) {
        is StructureStep.SetBlock -> {
            val target = blockExtras(step.target.extras)
            val expected = blockExtras(step.expected.extras)
            checkExtras(target, expected)
            value(29 + target.size + expected.size) {
                position(STRUCT_SET_BLOCK, worldId(step.at.world), step.at)
                putI32(17, blockDataId(step.target.data))
                putI32(21, blockDataId(step.expected.data))
                putI16(25, target.size.toShort())
                putI16(27, expected.size.toShort())
                writeBytes(29, target)
                writeBytes(29L + target.size, expected)
            }
        }

        is StructureStep.SpawnEntity -> entity(STRUCT_SPAWN_ENTITY, step.at, step.entity, step.shape, worldId, entityTypeId)
        is StructureStep.RemoveEntity -> entity(STRUCT_REMOVE_ENTITY, step.at, step.entity, step.shape, worldId, entityTypeId)
    }

    fun decodeStructureStep(
        v: MemorySegment,
        world: (Int) -> WorldId,
        blockData: (Int) -> BlockDataKey,
        entityType: (Int) -> EntityTypeKey,
    ): StructureStep {
        val at = BlockPos(world(v.i32(1)), v.i32(5), v.i32(9), v.i32(13))
        return when (val kind = v.i8(0)) {
            STRUCT_SET_BLOCK -> {
                val targetLen = v.i16(25).toInt() and 0xFFFF
                val expectedLen = v.i16(27).toInt() and 0xFFFF
                StructureStep.SetBlock(
                    at,
                    BlockShape(blockData(v.i32(17)), decodeBlockExtras(v.readBytes(29, targetLen))),
                    BlockShape(blockData(v.i32(21)), decodeBlockExtras(v.readBytes(29L + targetLen, expectedLen))),
                )
            }

            STRUCT_SPAWN_ENTITY -> StructureStep.SpawnEntity(at, UUID(v.i64(21), v.i64(29)), decodeShape(v, entityType))
            STRUCT_REMOVE_ENTITY -> StructureStep.RemoveEntity(at, UUID(v.i64(21), v.i64(29)), decodeShape(v, entityType))
            else -> error("unrecognized structure step kind: $kind")
        }
    }

    private fun entity(
        kind: Byte,
        at: BlockPos,
        entity: UUID,
        shape: EntityShape,
        worldId: (WorldId) -> Int,
        entityTypeId: (EntityTypeKey) -> Int,
    ): ByteArray {
        val extras = entityExtras(shape.extras)
        checkExtras(extras, ByteArray(0))
        return value(71 + extras.size) {
            position(kind, worldId(at.world), at)
            putI32(17, entityTypeId(shape.type))
            putI64(21, entity.mostSignificantBits)
            putI64(29, entity.leastSignificantBits)
            putI64(37, shape.x.toRawBits())
            putI64(45, shape.y.toRawBits())
            putI64(53, shape.z.toRawBits())
            putI32(61, shape.yaw.toRawBits())
            putI32(65, shape.pitch.toRawBits())
            putI16(69, extras.size.toShort())
            writeBytes(71, extras)
        }
    }

    private fun decodeShape(v: MemorySegment, entityType: (Int) -> EntityTypeKey): EntityShape = EntityShape(
        entityType(v.i32(17)),
        Double.fromBits(v.i64(37)),
        Double.fromBits(v.i64(45)),
        Double.fromBits(v.i64(53)),
        Float.fromBits(v.i32(61)),
        Float.fromBits(v.i32(65)),
        decodeEntityExtras(v.readBytes(71, v.i16(69).toInt() and 0xFFFF)),
    )

    private fun MemorySegment.position(kind: Byte, worldId: Int, at: BlockPos) {
        putI8(0, kind)
        putI32(1, worldId)
        putI32(5, at.x)
        putI32(9, at.y)
        putI32(13, at.z)
    }

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

    // Box "World change": 32-byte header + a kind-dependent tail

    const val CHANGE_BLOCK: Byte = 0
    const val CHANGE_ENTITY: Byte = 1

    const val WCHG_HEADER_BYTES = 32
    private const val BLOCK_TAIL_BYTES = 12
    private const val ENTITY_TAIL_BYTES = 24

    const val MAX_EXTRAS_BYTES = 0xFFFF

    private const val EXTRAS_OPAQUE: Byte = 0
    private const val EXTRAS_POSED: Byte = 1
    private const val POSE_BYTES = 33

    fun blockExtras(extras: BlockExtras?): ByteArray = when (extras) {
        null -> ByteArray(0)
        is BlockExtras.Opaque -> tagged(EXTRAS_OPAQUE, extras.nbt)
    }

    fun decodeBlockExtras(bytes: ByteArray): BlockExtras? = when {
        bytes.isEmpty() -> null
        bytes[0] == EXTRAS_OPAQUE -> BlockExtras.Opaque(bytes.copyOfRange(1, bytes.size))
        else -> error("unrecognized block extras tag: ${bytes[0]}")
    }

    fun entityExtras(extras: EntityExtras?): ByteArray = when (extras) {
        null -> ByteArray(0)
        is EntityExtras.Opaque -> tagged(EXTRAS_OPAQUE, extras.nbt)
    }

    fun decodeEntityExtras(bytes: ByteArray): EntityExtras? = when {
        bytes.isEmpty() -> null
        bytes[0] == EXTRAS_OPAQUE -> EntityExtras.Opaque(bytes.copyOfRange(1, bytes.size))
        else -> error("unrecognized entity extras tag: ${bytes[0]}")
    }

    fun entityShapePayload(shape: EntityShape?): ByteArray {
        if (shape == null) return ByteArray(0)
        val nbt = entityExtras(shape.extras)
        return value(POSE_BYTES + nbt.size) {
            putI8(0, EXTRAS_POSED)
            putI64(1, shape.x.toRawBits())
            putI64(9, shape.y.toRawBits())
            putI64(17, shape.z.toRawBits())
            putI32(25, shape.yaw.toRawBits())
            putI32(29, shape.pitch.toRawBits())
            writeBytes(33, nbt)
        }
    }

    fun decodeEntityShape(type: EntityTypeKey, at: BlockPos, payload: ByteArray): EntityShape? {
        if (payload.isEmpty()) return null
        val v = MemorySegment.ofArray(payload)
        return when (payload[0]) {
            EXTRAS_POSED -> {
                check(payload.size >= POSE_BYTES) { "posed extras truncated at ${payload.size} bytes" }
                val nested = if (payload.size == POSE_BYTES) ByteArray(0) else payload.copyOfRange(POSE_BYTES, payload.size)
                EntityShape(
                    type,
                    Double.fromBits(v.i64(1)),
                    Double.fromBits(v.i64(9)),
                    Double.fromBits(v.i64(17)),
                    Float.fromBits(v.i32(25)),
                    Float.fromBits(v.i32(29)),
                    decodeEntityExtras(nested),
                )
            }
            EXTRAS_OPAQUE -> EntityShape(
                type,
                at.x + 0.5,
                at.y.toDouble(),
                at.z + 0.5,
                extras = decodeEntityExtras(payload),
            )
            else -> error("unrecognized entity extras tag: ${payload[0]}")
        }
    }

    @Suppress("SameParameterValue")
    private fun tagged(tag: Byte, payload: ByteArray): ByteArray =
        ByteArray(1 + payload.size).also { it[0] = tag; payload.copyInto(it, 1) }

    fun blockChange(
        action: ActionKind,
        cause: CauseKind,
        causedByHolderId: Int,
        worldId: Int,
        x: Int,
        y: Int,
        z: Int,
        epochMillis: Long,
        beforeDataId: Int,
        afterDataId: Int,
        beforeExtras: ByteArray,
        afterExtras: ByteArray,
    ): ByteArray {
        checkExtras(beforeExtras, afterExtras)
        return value(WCHG_HEADER_BYTES + BLOCK_TAIL_BYTES + beforeExtras.size + afterExtras.size) {
        header(CHANGE_BLOCK, action, cause, causedByHolderId, worldId, x, y, z, epochMillis)
        putI32(32, beforeDataId)
        putI32(36, afterDataId)
        putI16(40, beforeExtras.size.toShort())
        putI16(42, afterExtras.size.toShort())
        writeBytes(44, beforeExtras)
        writeBytes(44L + beforeExtras.size, afterExtras)
        }
    }

    fun entityChange(
        action: ActionKind,
        cause: CauseKind,
        causedByHolderId: Int,
        worldId: Int,
        x: Int,
        y: Int,
        z: Int,
        epochMillis: Long,
        entityTypeId: Int,
        entity: UUID,
        beforeExtras: ByteArray,
        afterExtras: ByteArray,
    ): ByteArray {
        checkExtras(beforeExtras, afterExtras)
        return value(WCHG_HEADER_BYTES + ENTITY_TAIL_BYTES + beforeExtras.size + afterExtras.size) {
        header(CHANGE_ENTITY, action, cause, causedByHolderId, worldId, x, y, z, epochMillis)
        putI32(32, entityTypeId)
        putI64(36, entity.mostSignificantBits)
        putI64(44, entity.leastSignificantBits)
        putI16(52, beforeExtras.size.toShort())
        putI16(54, afterExtras.size.toShort())
        writeBytes(56, beforeExtras)
        writeBytes(56L + beforeExtras.size, afterExtras)
        }
    }

    fun wchgVersion(v: MemorySegment): Byte = v.i8(0)
    fun wchgKind(v: MemorySegment): Byte = v.i8(1)
    fun wchgAction(v: MemorySegment): ActionKind = ActionKind.entries[v.i8(2).toInt()]
    fun wchgCause(v: MemorySegment): CauseKind = CauseKind.entries[v.i8(3).toInt()]
    fun wchgCausedBy(v: MemorySegment): Int = v.i32(4)
    fun wchgWorldId(v: MemorySegment): Int = v.i32(8)
    fun wchgX(v: MemorySegment): Int = v.i32(12)
    fun wchgY(v: MemorySegment): Int = v.i32(16)
    fun wchgZ(v: MemorySegment): Int = v.i32(20)
    fun wchgEpochMillis(v: MemorySegment): Long = v.i64(24)

    fun blockChangeBefore(v: MemorySegment): Int = v.i32(32)
    fun blockChangeAfter(v: MemorySegment): Int = v.i32(36)
    fun blockChangeBeforeExtrasLength(v: MemorySegment): Int = v.i16(40).toInt() and 0xFFFF
    fun blockChangeAfterExtrasLength(v: MemorySegment): Int = v.i16(42).toInt() and 0xFFFF
    fun blockChangeBeforeExtras(v: MemorySegment): ByteArray = v.readBytes(44, blockChangeBeforeExtrasLength(v))

    fun blockChangeAfterExtras(v: MemorySegment): ByteArray =
        v.readBytes(44L + blockChangeBeforeExtrasLength(v), blockChangeAfterExtrasLength(v))

    fun entityChangeTypeId(v: MemorySegment): Int = v.i32(32)
    fun entityChangeUuid(v: MemorySegment): UUID = UUID(v.i64(36), v.i64(44))
    fun entityChangeBeforeExtrasLength(v: MemorySegment): Int = v.i16(52).toInt() and 0xFFFF
    fun entityChangeAfterExtrasLength(v: MemorySegment): Int = v.i16(54).toInt() and 0xFFFF
    fun entityChangeBeforeExtras(v: MemorySegment): ByteArray = v.readBytes(56, entityChangeBeforeExtrasLength(v))

    fun entityChangeAfterExtras(v: MemorySegment): ByteArray =
        v.readBytes(56L + entityChangeBeforeExtrasLength(v), entityChangeAfterExtrasLength(v))

    private fun checkExtras(before: ByteArray, after: ByteArray) {
        require(before.size <= MAX_EXTRAS_BYTES && after.size <= MAX_EXTRAS_BYTES) {
            "extras of ${before.size} / ${after.size} bytes do not fit a u16 length prefix"
        }
    }

    private fun MemorySegment.header(
        kind: Byte,
        action: ActionKind,
        cause: CauseKind,
        causedByHolderId: Int,
        worldId: Int,
        x: Int,
        y: Int,
        z: Int,
        epochMillis: Long,
    ) {
        putI8(0, VERSION)
        putI8(1, kind)
        putI8(2, action.ordinal.toByte())
        putI8(3, cause.ordinal.toByte())
        putI32(4, causedByHolderId)
        putI32(8, worldId)
        putI32(12, x)
        putI32(16, y)
        putI32(20, z)
        putI64(24, epochMillis)
    }

    // Box "Transaction": 24-byte header + 24 bytes per flow

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
        into.putI8(0, VERSION)
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
    fun flowKind(v: MemorySegment, i: Int): FlowKind = FlowKind.entries[v.i8((TXN_HEADER_BYTES + i * FLOW_BYTES + 12).toLong()).toInt()]

    fun flowQuantity(v: MemorySegment, i: Int): Long = v.i64((TXN_HEADER_BYTES + i * FLOW_BYTES + 16).toLong())

    // Box "Packed run": magic, count, then per part a length and its bytes

    private const val PACKED: Byte = 0x7F

    fun packed(parts: List<ByteArray>): ByteArray {
        var size = 5
        for (part in parts) size += 4 + part.size
        return value(size) {
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

    private inline fun value(size: Int, write: MemorySegment.() -> Unit): ByteArray {
        val bytes = ByteArray(size)
        MemorySegment.ofArray(bytes).write()
        return bytes
    }
}
