package com.tracel.storage.codec.records

import com.tracel.engine.rollback.plan.LotContribution
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.world.*
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
import java.util.*

object Rollback {
    private const val STRUCT_SET_BLOCK: Byte = 0
    private const val STRUCT_SPAWN_ENTITY: Byte = 1
    private const val STRUCT_REMOVE_ENTITY: Byte = 2

    private const val STEP_TAKE: Byte = 0
    private const val STEP_MINT: Byte = 1
    private const val STEP_DEBT: Byte = 2
    private const val STEP_UNMAKE: Byte = 3

    fun structureStep(
        step: StructureStep,
        worldId: (WorldId) -> Int,
        blockDataId: (BlockDataKey) -> Int,
        entityTypeId: (EntityTypeKey) -> Int,
    ): ByteArray = when (step) {
        is StructureStep.SetBlock -> {
            val target = World.blockExtras(step.target.extras)
            val expected = World.blockExtras(step.expected.extras)
            World.checkExtras(target, expected)
            recordBytes(29 + target.size + expected.size) {
                position(STRUCT_SET_BLOCK, worldId(step.at.world), step.at)
                putI32(17, blockDataId(step.target.data))
                putI32(21, blockDataId(step.expected.data))
                putI16(25, target.size.toShort())
                putI16(27, expected.size.toShort())
                writeBytes(29, target)
                writeBytes(29L + target.size, expected)
            }
        }

        is StructureStep.SpawnEntity -> entity(
            STRUCT_SPAWN_ENTITY,
            step.at,
            step.entity,
            step.shape,
            worldId,
            entityTypeId
        )

        is StructureStep.RemoveEntity -> entity(
            STRUCT_REMOVE_ENTITY,
            step.at,
            step.entity,
            step.shape,
            worldId,
            entityTypeId
        )
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun decodeStructureInto(
        v: MemorySegment,
        noinline world: (Int) -> WorldId,
        noinline blockData: (Int) -> BlockDataKey,
        noinline entityType: (Int) -> EntityTypeKey,
        out: MutableList<StructureStep>,
    ) {
        if (v.i8(0) != structureSectionTag()) {
            out += decodeStructureStep(v, world, blockData, entityType)
            return
        }
        val tail = Section.structSectionTail()
        val at = world(v.i32(1))
        val cornerX = v.i32(5)
        val cornerY = v.i32(9)
        val cornerZ = v.i32(13)
        Section.forEachSectionPosition(v, tail) { index, packed ->
            val extras = Section.sectionExtras(v, index, tail)
            out += StructureStep.SetBlock(
                BlockPos(
                    at,
                    cornerX + Section.sectionPositionX(packed),
                    cornerY + Section.sectionPositionY(packed),
                    cornerZ + Section.sectionPositionZ(packed),
                ),
                BlockShape(
                    blockData(Section.sectionBefore(v, index, tail)),
                    World.decodeBlockExtras(extras?.before ?: EMPTY_BYTES)
                ),
                BlockShape(
                    blockData(Section.sectionAfter(v, index, tail)),
                    World.decodeBlockExtras(extras?.after ?: EMPTY_BYTES)
                ),
            )
        }
    }

    fun structureSectionTag(): Byte = Section.STRUCT_SECTION

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
                    BlockShape(blockData(v.i32(17)), World.decodeBlockExtras(v.readBytes(29, targetLen))),
                    BlockShape(
                        blockData(v.i32(21)),
                        World.decodeBlockExtras(v.readBytes(29L + targetLen, expectedLen))
                    ),
                )
            }

            STRUCT_SPAWN_ENTITY -> StructureStep.SpawnEntity(at, UUID(v.i64(21), v.i64(29)), decodeShape(v, entityType))
            STRUCT_REMOVE_ENTITY -> StructureStep.RemoveEntity(
                at,
                UUID(v.i64(21), v.i64(29)),
                decodeShape(v, entityType)
            )

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
        val extras = World.entityExtras(shape.extras)
        World.checkExtras(extras, ByteArray(0))
        return recordBytes(71 + extras.size) {
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
        World.decodeEntityExtras(v.readBytes(71, v.i16(69).toInt() and 0xFFFF)),
    )

    private fun MemorySegment.position(kind: Byte, worldId: Int, at: BlockPos) {
        putI8(0, kind)
        putI32(1, worldId)
        putI32(5, at.x)
        putI32(9, at.y)
        putI32(13, at.z)
    }

    fun step(step: RollbackStep, holderId: (HolderId) -> Int): ByteArray = when (step) {
        is RollbackStep.Take -> recordBytes(21) {
            putI8(0, STEP_TAKE); putI64(1, step.lotId.raw); putI64(9, step.quantity.raw)
            putI32(17, holderId(step.holder))
        }

        is RollbackStep.Mint -> recordBytes(18) {
            putI8(0, STEP_MINT); putI64(1, step.lotId.raw); putI64(9, step.quantity.raw)
            putI8(17, step.reason.ordinal.toByte())
        }

        is RollbackStep.Debt -> recordBytes(33) {
            putI8(0, STEP_DEBT); putI64(1, step.lotId.raw); putI64(9, step.quantity.raw)
            putI64(17, step.player.mostSignificantBits); putI64(25, step.player.leastSignificantBits)
        }

        is RollbackStep.Unmake -> recordBytes(23 + step.inputs.size * 16) {
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
                RollbackStep.Unmake(LotId(v.i64(1)), inputs, TxnId(v.i64(9)), holder(v.i32(17)))
            }

            else -> error("unrecognized rollback step kind: $kind")
        }
}
