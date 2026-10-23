package com.tracel.storage.codec.records

import com.tracel.model.cause.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.OpaqueBytes
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
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

object World {
    const val CHANGE_BLOCK: Byte = 0
    const val CHANGE_ENTITY: Byte = 1
    const val CHANGE_SECTION: Byte = 2

    const val WCHG_HEADER_BYTES = 32
    const val MAX_EXTRAS_BYTES = OpaqueBytes.MAX_BYTES

    private const val BLOCK_TAIL_BYTES = 12
    private const val ENTITY_TAIL_BYTES = 24
    private const val EXTRAS_OPAQUE: Byte = 0
    private const val EXTRAS_POSED: Byte = 1
    private const val EXTRAS_FALLING: Byte = 2
    private const val EXTRAS_LEASHED: Byte = 3
    private const val EXTRAS_RIDING: Byte = 4
    private const val POSE_BYTES = 33
    private const val BLOCK_EXTRAS_AT = 44L
    private const val ENTITY_EXTRAS_AT = 56L

    const val LINK_BYTES = OpaqueBytes.LINK_BYTES

    fun blockExtras(extras: BlockExtras?): ByteArray = when (extras) {
        null -> ByteArray(0)
        is BlockExtras.Opaque -> tagged(EXTRAS_OPAQUE, extras.bytes)
    }

    fun decodeBlockExtras(bytes: ByteArray): BlockExtras? = decodeBlockExtras(MemorySegment.ofArray(bytes), 0L, bytes.size)

    fun decodeBlockExtras(v: MemorySegment, at: Long, length: Int): BlockExtras? {
        if (length == 0) return null
        val tag = v.i8(at)
        if (tag != EXTRAS_OPAQUE) error("unrecognized block extras tag: $tag.")
        return BlockExtras.Opaque(v.readBytes(at + 1, length - 1))
    }

    fun blockChangeBeforeBlockExtras(v: MemorySegment): BlockExtras? =
        decodeBlockExtras(v, BLOCK_EXTRAS_AT, blockChangeBeforeExtrasLength(v))

    fun blockChangeAfterBlockExtras(v: MemorySegment): BlockExtras? =
        decodeBlockExtras(v, BLOCK_EXTRAS_AT + blockChangeBeforeExtrasLength(v), blockChangeAfterExtrasLength(v))

    fun entityExtras(extras: EntityExtras?): ByteArray = when (extras) {
        null -> ByteArray(0)
        is EntityExtras.Opaque -> tagged(EXTRAS_OPAQUE, extras.bytes)
        is EntityExtras.Falling -> tagged(EXTRAS_FALLING, extras.data.value.toByteArray(Charsets.UTF_8))
        is EntityExtras.Leashed -> link(EXTRAS_LEASHED, extras.holder, extras.rest)
        is EntityExtras.Riding -> link(EXTRAS_RIDING, extras.vehicle, extras.rest)
    }

    private fun link(tag: Byte, target: UUID, rest: EntityExtras?): ByteArray {
        val nested = entityExtras(rest)
        return recordBytes(LINK_BYTES + nested.size) {
            putI8(0, tag)
            putI64(1, target.mostSignificantBits)
            putI64(9, target.leastSignificantBits)
            writeBytes(LINK_BYTES.toLong(), nested)
        }
    }

    fun decodeEntityExtras(bytes: ByteArray): EntityExtras? = decodeEntityExtras(MemorySegment.ofArray(bytes), 0L, bytes.size)

    fun decodeEntityExtras(v: MemorySegment, at: Long, length: Int): EntityExtras? {
        if (length == 0) return null
        return when (val tag = v.i8(at)) {
            EXTRAS_OPAQUE -> EntityExtras.Opaque(v.readBytes(at + 1, length - 1))
            EXTRAS_FALLING -> EntityExtras.Falling(BlockDataKey(String(v.readBytes(at + 1, length - 1), Charsets.UTF_8)))
            EXTRAS_LEASHED -> EntityExtras.Leashed(linkTarget(v, at, length), linkRest(v, at, length))
            EXTRAS_RIDING -> EntityExtras.Riding(linkTarget(v, at, length), linkRest(v, at, length))
            else -> error("unrecognized entity extras tag: $tag")
        }
    }

    private fun linkTarget(v: MemorySegment, at: Long, length: Int): UUID {
        check(length >= LINK_BYTES) { "linked extras truncated at $length bytes." }
        return UUID(v.i64(at + 1), v.i64(at + 9))
    }

    private fun linkRest(v: MemorySegment, at: Long, length: Int): EntityExtras? =
        decodeEntityExtras(v, at + LINK_BYTES, length - LINK_BYTES)

    fun entityShapePayload(shape: EntityShape?): ByteArray {
        if (shape == null) return ByteArray(0)
        val nbt = entityExtras(shape.extras)
        return recordBytes(POSE_BYTES + nbt.size) {
            putI8(0, EXTRAS_POSED)
            putI64(1, shape.x.toRawBits())
            putI64(9, shape.y.toRawBits())
            putI64(17, shape.z.toRawBits())
            putI32(25, shape.yaw.toRawBits())
            putI32(29, shape.pitch.toRawBits())
            writeBytes(33, nbt)
        }
    }

    fun decodeEntityShape(type: EntityTypeKey, at: BlockPos, payload: ByteArray): EntityShape? =
        decodeEntityShape(type, at, MemorySegment.ofArray(payload), 0L, payload.size)

    fun decodeEntityShape(type: EntityTypeKey, at: BlockPos, v: MemorySegment, offset: Long, length: Int): EntityShape? {
        if (length == 0) return null
        return when (val tag = v.i8(offset)) {
            EXTRAS_POSED -> {
                check(length >= POSE_BYTES) { "posed extras truncated at $length bytes." }
                EntityShape(
                    type,
                    Double.fromBits(v.i64(offset + 1)),
                    Double.fromBits(v.i64(offset + 9)),
                    Double.fromBits(v.i64(offset + 17)),
                    Float.fromBits(v.i32(offset + 25)),
                    Float.fromBits(v.i32(offset + 29)),
                    decodeEntityExtras(v, offset + POSE_BYTES, length - POSE_BYTES),
                )
            }

            EXTRAS_OPAQUE -> EntityShape(
                type,
                at.x + 0.5,
                at.y.toDouble(),
                at.z + 0.5,
                extras = decodeEntityExtras(v, offset, length),
            )

            else -> error("unrecognized entity extras tag: $tag.")
        }
    }

    fun entityChangeBeforeShape(type: EntityTypeKey, at: BlockPos, v: MemorySegment): EntityShape? =
        decodeEntityShape(type, at, v, ENTITY_EXTRAS_AT, entityChangeBeforeExtrasLength(v))

    fun entityChangeAfterShape(type: EntityTypeKey, at: BlockPos, v: MemorySegment): EntityShape? =
        decodeEntityShape(
            type, at, v, ENTITY_EXTRAS_AT + entityChangeBeforeExtrasLength(v), entityChangeAfterExtrasLength(v),
        )

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
        return recordBytes(WCHG_HEADER_BYTES + BLOCK_TAIL_BYTES + beforeExtras.size + afterExtras.size) {
            writeWchgHeader(CHANGE_BLOCK, action, cause, causedByHolderId, worldId, x, y, z, epochMillis)
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
        return recordBytes(WCHG_HEADER_BYTES + ENTITY_TAIL_BYTES + beforeExtras.size + afterExtras.size) {
            writeWchgHeader(CHANGE_ENTITY, action, cause, causedByHolderId, worldId, x, y, z, epochMillis)
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
    fun blockChangeBeforeExtras(v: MemorySegment): ByteArray = v.readBytes(BLOCK_EXTRAS_AT, blockChangeBeforeExtrasLength(v))
    fun blockChangeAfterExtras(v: MemorySegment): ByteArray =
        v.readBytes(BLOCK_EXTRAS_AT + blockChangeBeforeExtrasLength(v), blockChangeAfterExtrasLength(v))

    fun entityChangeTypeId(v: MemorySegment): Int = v.i32(32)
    fun entityChangeUuid(v: MemorySegment): UUID = UUID(v.i64(36), v.i64(44))
    fun entityChangeBeforeExtrasLength(v: MemorySegment): Int = v.i16(52).toInt() and 0xFFFF
    fun entityChangeAfterExtrasLength(v: MemorySegment): Int = v.i16(54).toInt() and 0xFFFF
    fun entityChangeBeforeExtras(v: MemorySegment): ByteArray = v.readBytes(ENTITY_EXTRAS_AT, entityChangeBeforeExtrasLength(v))
    fun entityChangeAfterExtras(v: MemorySegment): ByteArray =
        v.readBytes(ENTITY_EXTRAS_AT + entityChangeBeforeExtrasLength(v), entityChangeAfterExtrasLength(v))

    internal fun checkExtras(before: ByteArray, after: ByteArray) {
        require(before.size <= MAX_EXTRAS_BYTES && after.size <= MAX_EXTRAS_BYTES) {
            "extras of ${before.size} / ${after.size} bytes do not fit a u16 length prefix."
        }
    }

    @Suppress("SameParameterValue")
    private fun tagged(tag: Byte, payload: ByteArray): ByteArray =
        ByteArray(1 + payload.size).also { it[0] = tag; payload.copyInto(it, 1) }
}

internal fun MemorySegment.writeWchgHeader(
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
    putI8(0, CODEC_VERSION)
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
