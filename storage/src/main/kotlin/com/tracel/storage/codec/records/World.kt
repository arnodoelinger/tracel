package com.tracel.storage.codec.records

import com.tracel.annotations.CauseKind
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.model.world.ActionKind
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
    const val MAX_EXTRAS_BYTES = 0xFFFF

    private const val BLOCK_TAIL_BYTES = 12
    private const val ENTITY_TAIL_BYTES = 24
    private const val EXTRAS_OPAQUE: Byte = 0
    private const val EXTRAS_POSED: Byte = 1
    private const val EXTRAS_FALLING: Byte = 2
    private const val EXTRAS_LEASHED: Byte = 3
    private const val EXTRAS_RIDING: Byte = 4
    private const val POSE_BYTES = 33

    const val LINK_BYTES = 17

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

    fun decodeEntityExtras(bytes: ByteArray): EntityExtras? = when {
        bytes.isEmpty() -> null
        bytes[0] == EXTRAS_OPAQUE -> EntityExtras.Opaque(bytes.copyOfRange(1, bytes.size))
        bytes[0] == EXTRAS_FALLING ->
            EntityExtras.Falling(BlockDataKey(String(bytes, 1, bytes.size - 1, Charsets.UTF_8)))

        bytes[0] == EXTRAS_LEASHED -> {
            val (target, rest) = decodeLink(bytes)
            EntityExtras.Leashed(target, rest)
        }

        bytes[0] == EXTRAS_RIDING -> {
            val (target, rest) = decodeLink(bytes)
            EntityExtras.Riding(target, rest)
        }

        else -> error("unrecognized entity extras tag: ${bytes[0]}")
    }

    private fun decodeLink(bytes: ByteArray): Pair<UUID, EntityExtras?> {
        check(bytes.size >= LINK_BYTES) { "linked extras truncated at ${bytes.size} bytes" }
        val v = MemorySegment.ofArray(bytes)
        return UUID(v.i64(1), v.i64(9)) to decodeEntityExtras(bytes.copyOfRange(LINK_BYTES, bytes.size))
    }

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

    fun decodeEntityShape(type: EntityTypeKey, at: BlockPos, payload: ByteArray): EntityShape? {
        if (payload.isEmpty()) return null
        val v = MemorySegment.ofArray(payload)
        return when (payload[0]) {
            EXTRAS_POSED -> {
                check(payload.size >= POSE_BYTES) { "posed extras truncated at ${payload.size} bytes" }
                val nested =
                    if (payload.size == POSE_BYTES) ByteArray(0) else payload.copyOfRange(POSE_BYTES, payload.size)
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

    internal fun checkExtras(before: ByteArray, after: ByteArray) {
        require(before.size <= MAX_EXTRAS_BYTES && after.size <= MAX_EXTRAS_BYTES) {
            "extras of ${before.size} / ${after.size} bytes do not fit a u16 length prefix"
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
