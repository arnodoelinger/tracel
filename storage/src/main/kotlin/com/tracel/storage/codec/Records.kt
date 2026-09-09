package com.tracel.storage.codec

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.*
import com.tracel.storage.codec.records.*
import com.tracel.storage.codec.records.Packed
import java.lang.foreign.MemorySegment
import java.util.*

/** Packed value layouts. */
object Records {
    const val VERSION: Byte = Packed.VERSION
    const val INLINE_NEEDS_RECORD: Byte = Index.INLINE_NEEDS_RECORD
    const val EDGE_SPLIT: Byte = Lot.EDGE_SPLIT
    const val EDGE_TRANSFORM: Byte = Lot.EDGE_TRANSFORM
    const val EDGE_COMPENSATE: Byte = Lot.EDGE_COMPENSATE
    const val CHANGE_BLOCK: Byte = World.CHANGE_BLOCK
    const val CHANGE_ENTITY: Byte = World.CHANGE_ENTITY
    const val CHANGE_SECTION: Byte = World.CHANGE_SECTION
    const val SECTION_POSITIONS: Int = Section.SECTION_POSITIONS

    fun lot(itemKeyId: Int, quantity: Long, createdBy: Long) = Lot.lot(itemKeyId, quantity, createdBy)
    fun lotItemKeyId(v: MemorySegment) = Lot.lotItemKeyId(v)
    fun lotQuantity(v: MemorySegment) = Lot.lotQuantity(v)
    fun lotCreatedBy(v: MemorySegment) = Lot.lotCreatedBy(v)
    fun placement(lotId: Long, remaining: Long) = Lot.placement(lotId, remaining)
    fun placementLotId(v: MemorySegment) = Lot.placementLotId(v)
    fun placementRemaining(v: MemorySegment) = Lot.placementRemaining(v)
    fun placementRev(itemKeyId: Int, fifoSeq: Long) = Lot.placementRev(itemKeyId, fifoSeq)
    fun placementRevItemKeyId(v: MemorySegment) = Lot.placementRevItemKeyId(v)
    fun placementRevFifoSeq(v: MemorySegment) = Lot.placementRevFifoSeq(v)
    fun long(value: Long) = Packed.long(value)
    fun asLong(v: MemorySegment) = Packed.asLong(v)
    fun int(value: Int) = Packed.int(value)
    fun asInt(v: MemorySegment) = Packed.asInt(v)
    fun logKind(kind: LogKind) = Index.logKind(kind)
    fun logKind(kind: LogKind, epochMillis: Long, cause: CauseKind, x: Int, y: Int, z: Int) =
        Index.logKind(kind, epochMillis, cause, x, y, z)

    fun logKindInline(
        kind: LogKind,
        cause: CauseKind,
        x: Int,
        y: Int,
        z: Int,
        action: ActionKind,
        beforeDataId: Int,
        afterDataId: Int,
        causedById: Int,
        flags: Byte
    ) =
        Index.logKindInline(kind, cause, x, y, z, action, beforeDataId, afterDataId, causedById, flags)

    fun asLogKind(v: MemorySegment) = Index.asLogKind(v)
    fun logKindMillis(v: MemorySegment) = Index.logKindMillis(v)
    fun logKindCause(v: MemorySegment) = Index.logKindCause(v)
    fun logKindX(v: MemorySegment) = Index.logKindX(v)
    fun logKindY(v: MemorySegment) = Index.logKindY(v)
    fun logKindZ(v: MemorySegment) = Index.logKindZ(v)
    fun logKindHasInline(v: MemorySegment) = Index.logKindHasInline(v)
    fun logKindAction(v: MemorySegment) = Index.logKindAction(v)
    fun logKindBefore(v: MemorySegment) = Index.logKindBefore(v)
    fun logKindAfter(v: MemorySegment) = Index.logKindAfter(v)
    fun logKindCausedBy(v: MemorySegment) = Index.logKindCausedBy(v)
    fun edge(kind: Byte, quantity: Long, reference: Long, producedAtHolderId: Int) =
        Lot.edge(kind, quantity, reference, producedAtHolderId)

    fun edgeKind(v: MemorySegment) = Lot.edgeKind(v)
    fun edgeQuantity(v: MemorySegment) = Lot.edgeQuantity(v)
    fun edgeReference(v: MemorySegment) = Lot.edgeReference(v)
    fun edgeProducedAt(v: MemorySegment) = Lot.edgeProducedAt(v)
    fun lease(jobId: Long, acquiredAtMillis: Long) = Job.lease(jobId, acquiredAtMillis)
    fun leaseJobId(v: MemorySegment) = Job.leaseJobId(v)
    fun leaseAcquiredAt(v: MemorySegment) = Job.leaseAcquiredAt(v)
    fun pending(itemKeyId: Int, delta: Long, jobId: Long, createdMillis: Long) =
        Job.pending(itemKeyId, delta, jobId, createdMillis)

    fun pendingItemKeyId(v: MemorySegment) = Job.pendingItemKeyId(v)
    fun pendingDelta(v: MemorySegment) = Job.pendingDelta(v)
    fun pendingJobId(v: MemorySegment) = Job.pendingJobId(v)
    fun rbJob(
        restoreToHolderId: Int,
        stepCount: Int,
        createCount: Int,
        destroyCount: Int,
        targetTimeMillis: Long,
        hasTargetTime: Boolean,
        executedAtMillis: Long,
    ) = Job.rbJob(restoreToHolderId, stepCount, createCount, destroyCount, targetTimeMillis, hasTargetTime, executedAtMillis)

    fun rbJobRestoreTo(v: MemorySegment) = Job.rbJobRestoreTo(v)
    fun rbJobStepCount(v: MemorySegment) = Job.rbJobStepCount(v)
    fun rbJobCreateCount(v: MemorySegment) = Job.rbJobCreateCount(v)
    fun rbJobDestroyCount(v: MemorySegment) = Job.rbJobDestroyCount(v)
    fun rbJobTargetTime(v: MemorySegment) = Job.rbJobTargetTime(v)
    fun rbJobExecutedAt(v: MemorySegment) = Job.rbJobExecutedAt(v)
    fun structureStep(
        step: StructureStep,
        worldId: (WorldId) -> Int,
        blockDataId: (BlockDataKey) -> Int,
        entityTypeId: (EntityTypeKey) -> Int
    ) =
        Rollback.structureStep(step, worldId, blockDataId, entityTypeId)

    @Suppress("NOTHING_TO_INLINE")
    inline fun decodeStructureInto(
        v: MemorySegment,
        noinline world: (Int) -> WorldId,
        noinline blockData: (Int) -> BlockDataKey,
        noinline entityType: (Int) -> EntityTypeKey,
        out: MutableList<StructureStep>,
    ) = Rollback.decodeStructureInto(v, world, blockData, entityType, out)

    fun step(step: RollbackStep, holderId: (HolderId) -> Int) = Rollback.step(step, holderId)
    fun decodeStep(v: MemorySegment, holder: (Int) -> HolderId) = Rollback.decodeStep(v, holder)
    fun blockExtras(extras: BlockExtras?) = World.blockExtras(extras)
    fun decodeBlockExtras(bytes: ByteArray) = World.decodeBlockExtras(bytes)
    fun entityExtras(extras: EntityExtras?) = World.entityExtras(extras)
    fun decodeEntityExtras(bytes: ByteArray) = World.decodeEntityExtras(bytes)
    fun entityShapePayload(shape: EntityShape?) = World.entityShapePayload(shape)
    fun decodeEntityShape(type: EntityTypeKey, at: BlockPos, payload: ByteArray) =
        World.decodeEntityShape(type, at, payload)

    fun blockChange(
        action: ActionKind, cause: CauseKind, causedByHolderId: Int, worldId: Int,
        x: Int, y: Int, z: Int, epochMillis: Long, beforeDataId: Int, afterDataId: Int,
        beforeExtras: ByteArray, afterExtras: ByteArray,
    ) = World.blockChange(
        action, cause, causedByHolderId, worldId, x, y, z, epochMillis,
        beforeDataId, afterDataId, beforeExtras, afterExtras,
    )

    fun entityChange(
        action: ActionKind, cause: CauseKind, causedByHolderId: Int, worldId: Int,
        x: Int, y: Int, z: Int, epochMillis: Long, entityTypeId: Int, entity: UUID,
        beforeExtras: ByteArray, afterExtras: ByteArray,
    ) = World.entityChange(
        action, cause, causedByHolderId, worldId, x, y, z, epochMillis,
        entityTypeId, entity, beforeExtras, afterExtras,
    )

    fun sectionDelta(
        action: ActionKind, cause: CauseKind, causedByHolderId: Int, worldId: Int,
        sectionX: Int, sectionY: Int, sectionZ: Int, epochMillis: Long, baseSeq: Long,
        positions: IntArray, count: Int, before: IntArray, after: IntArray,
        extras: List<SectionExtras> = emptyList(),
    ) = Section.sectionDelta(
        action, cause, causedByHolderId, worldId, sectionX, sectionY, sectionZ,
        epochMillis, baseSeq, positions, count, before, after, extras,
    )

    fun structureSection(
        worldId: Int, sectionX: Int, sectionY: Int, sectionZ: Int,
        positions: IntArray, count: Int, target: IntArray, expected: IntArray,
        extras: List<SectionExtras>,
    ) = Section.structureSection(worldId, sectionX, sectionY, sectionZ, positions, count, target, expected, extras)

    fun sectionBaseSeq(v: MemorySegment) = Section.sectionBaseSeq(v)
    fun sectionCount(v: MemorySegment, tailAt: Int = Section.worldSectionTail()) = Section.sectionCount(v, tailAt)
    fun sectionPaletteSize(v: MemorySegment, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionPaletteSize(v, tailAt)

    fun sectionPaletteAt(v: MemorySegment, slot: Int, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionPaletteAt(v, slot, tailAt)

    fun sectionBefore(v: MemorySegment, index: Int, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionBefore(v, index, tailAt)

    fun sectionAfter(v: MemorySegment, index: Int, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionAfter(v, index, tailAt)

    inline fun forEachSectionPosition(
        v: MemorySegment,
        tailAt: Int = Section.worldSectionTail(),
        action: (index: Int, packed: Int) -> Unit,
    ) = Section.forEachSectionPosition(v, tailAt, action)

    fun sectionIndexOf(v: MemorySegment, packed: Int, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionIndexOf(v, packed, tailAt)

    fun sectionExtras(v: MemorySegment, index: Int, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionExtras(v, index, tailAt)

    fun sectionIsBitmap(v: MemorySegment, tailAt: Int = Section.worldSectionTail()) =
        Section.sectionIsBitmap(v, tailAt)

    fun packSectionPosition(x: Int, y: Int, z: Int) = Section.packSectionPosition(x, y, z)
    fun sectionPositionX(packed: Int) = Section.sectionPositionX(packed)
    fun sectionPositionZ(packed: Int) = Section.sectionPositionZ(packed)
    fun sectionPositionY(packed: Int) = Section.sectionPositionY(packed)
    fun logKindSection(
        kind: LogKind, cause: CauseKind, action: ActionKind,
        sectionX: Int, sectionY: Int, sectionZ: Int, count: Int, causedById: Int,
    ) = Index.logKindSection(kind, cause, action, sectionX, sectionY, sectionZ, count, causedById)

    fun wchgVersion(v: MemorySegment) = World.wchgVersion(v)
    fun wchgKind(v: MemorySegment) = World.wchgKind(v)
    fun wchgAction(v: MemorySegment) = World.wchgAction(v)
    fun wchgCause(v: MemorySegment) = World.wchgCause(v)
    fun wchgCausedBy(v: MemorySegment) = World.wchgCausedBy(v)
    fun wchgWorldId(v: MemorySegment) = World.wchgWorldId(v)
    fun wchgX(v: MemorySegment) = World.wchgX(v)
    fun wchgY(v: MemorySegment) = World.wchgY(v)
    fun wchgZ(v: MemorySegment) = World.wchgZ(v)
    fun wchgEpochMillis(v: MemorySegment) = World.wchgEpochMillis(v)
    fun blockChangeBefore(v: MemorySegment) = World.blockChangeBefore(v)
    fun blockChangeAfter(v: MemorySegment) = World.blockChangeAfter(v)
    fun blockChangeBeforeExtrasLength(v: MemorySegment) = World.blockChangeBeforeExtrasLength(v)
    fun blockChangeAfterExtrasLength(v: MemorySegment) = World.blockChangeAfterExtrasLength(v)
    fun blockChangeBeforeExtras(v: MemorySegment) = World.blockChangeBeforeExtras(v)
    fun blockChangeAfterExtras(v: MemorySegment) = World.blockChangeAfterExtras(v)
    fun entityChangeTypeId(v: MemorySegment) = World.entityChangeTypeId(v)
    fun entityChangeUuid(v: MemorySegment) = World.entityChangeUuid(v)
    fun entityChangeBeforeExtrasLength(v: MemorySegment) = World.entityChangeBeforeExtrasLength(v)
    fun entityChangeAfterExtrasLength(v: MemorySegment) = World.entityChangeAfterExtrasLength(v)
    fun entityChangeBeforeExtras(v: MemorySegment) = World.entityChangeBeforeExtras(v)
    fun entityChangeAfterExtras(v: MemorySegment) = World.entityChangeAfterExtras(v)
    fun transactionSize(flowCount: Int) = Txn.transactionSize(flowCount)
    fun writeTransactionHeader(
        into: MemorySegment, cause: CauseKind, flowCount: Int, causedByHolderId: Int,
        txnId: Long, epochMillis: Long, worldId: Int = 0, x: Int = 0, y: Int = 0, z: Int = 0,
    ) = Txn.writeTransactionHeader(into, cause, flowCount, causedByHolderId, txnId, epochMillis, worldId, x, y, z)

    fun txnWorldId(v: MemorySegment) = Txn.txnWorldId(v)
    fun txnX(v: MemorySegment) = Txn.txnX(v)
    fun txnY(v: MemorySegment) = Txn.txnY(v)
    fun txnZ(v: MemorySegment) = Txn.txnZ(v)
    fun writeFlow(
        into: MemorySegment, index: Int, itemKeyId: Int, sourceHolderId: Int,
        destinationHolderId: Int, kind: FlowKind, quantity: Long,
    ) = Txn.writeFlow(into, index, itemKeyId, sourceHolderId, destinationHolderId, kind, quantity)

    fun txnVersion(v: MemorySegment) = Txn.txnVersion(v)
    fun txnCause(v: MemorySegment) = Txn.txnCause(v)
    fun txnFlowCount(v: MemorySegment) = Txn.txnFlowCount(v)
    fun txnCausedBy(v: MemorySegment) = Txn.txnCausedBy(v)
    fun txnId(v: MemorySegment) = Txn.txnId(v)
    fun txnEpochMillis(v: MemorySegment) = Txn.txnEpochMillis(v)
    fun flowItemKeyId(v: MemorySegment, i: Int) = Txn.flowItemKeyId(v, i)
    fun flowSource(v: MemorySegment, i: Int) = Txn.flowSource(v, i)
    fun flowDestination(v: MemorySegment, i: Int) = Txn.flowDestination(v, i)
    fun flowKind(v: MemorySegment, i: Int) = Txn.flowKind(v, i)
    fun flowQuantity(v: MemorySegment, i: Int) = Txn.flowQuantity(v, i)
    fun packed(parts: List<ByteArray>) = Packed.packed(parts)
    fun forEachPacked(v: MemorySegment, action: (MemorySegment) -> Unit) = Packed.forEachPacked(v, action)
}
