package com.tracel.storage.codec.records

import com.tracel.annotations.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.model.world.LogKind
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import java.lang.foreign.MemorySegment

object Index {
    const val LOG_KIND_POSITION_BYTES: Int = 22
    const val LOG_KIND_INLINE_BYTES: Int = 28
    const val LOG_KIND_SECTION_BYTES: Int = 24
    const val INLINE_NEEDS_RECORD: Byte = 1

    fun logKind(kind: LogKind): ByteArray = recordBytes(1) { putI8(0, kind.ordinal.toByte()) }

    fun logKind(kind: LogKind, epochMillis: Long, cause: CauseKind, x: Int, y: Int, z: Int): ByteArray =
        recordBytes(LOG_KIND_POSITION_BYTES) {
            putI8(0, kind.ordinal.toByte())
            putI64(1, epochMillis)
            putI8(9, cause.ordinal.toByte())
            putI32(10, x)
            putI32(14, y)
            putI32(18, z)
        }

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
        flags: Byte,
    ): ByteArray = recordBytes(LOG_KIND_INLINE_BYTES) {
        putI8(0, kind.ordinal.toByte())
        putI8(1, cause.ordinal.toByte())
        putI8(2, action.ordinal.toByte())
        putI8(3, flags)
        putI32(4, x)
        putI32(8, y)
        putI32(12, z)
        putI32(16, beforeDataId)
        putI32(20, afterDataId)
        putI32(24, causedById)
    }

    fun asLogKind(v: MemorySegment): LogKind = LogKind.entries[v.i8(0).toInt()]

    private fun isInline(v: MemorySegment): Boolean = v.byteSize() == LOG_KIND_INLINE_BYTES.toLong()

    fun logKindMillis(v: MemorySegment): Long? = when {
        isInline(v) || logKindIsSection(v) -> null
        v.byteSize() >= 9 -> v.i64(1)
        else -> null
    }

    fun logKindCause(v: MemorySegment): CauseKind? = when {
        isInline(v) || logKindIsSection(v) -> CauseKind.entries[v.i8(1).toInt() and 0xFF]
        v.byteSize() >= 10 -> CauseKind.entries[v.i8(9).toInt() and 0xFF]
        else -> null
    }

    fun logKindX(v: MemorySegment): Int? = when {
        logKindIsSection(v) -> null
        isInline(v) -> v.i32(4)
        v.byteSize() >= LOG_KIND_POSITION_BYTES -> v.i32(10)
        else -> null
    }

    fun logKindY(v: MemorySegment): Int? = when {
        logKindIsSection(v) -> null
        isInline(v) -> v.i32(8)
        v.byteSize() >= LOG_KIND_POSITION_BYTES -> v.i32(14)
        else -> null
    }

    fun logKindZ(v: MemorySegment): Int? = when {
        logKindIsSection(v) -> null
        isInline(v) -> v.i32(12)
        v.byteSize() >= LOG_KIND_POSITION_BYTES -> v.i32(18)
        else -> null
    }

    fun logKindHasInline(v: MemorySegment): Boolean = isInline(v) && v.i8(3) != INLINE_NEEDS_RECORD

    fun logKindAction(v: MemorySegment): ActionKind = ActionKind.entries[v.i8(2).toInt() and 0xFF]
    fun logKindBefore(v: MemorySegment): Int = v.i32(16)
    fun logKindAfter(v: MemorySegment): Int = v.i32(20)
    fun logKindCausedBy(v: MemorySegment): Int = v.i32(24)

    fun logKindSection(
        kind: LogKind,
        cause: CauseKind,
        action: ActionKind,
        sectionX: Int,
        sectionY: Int,
        sectionZ: Int,
        count: Int,
        causedById: Int,
    ): ByteArray = recordBytes(LOG_KIND_SECTION_BYTES) {
        putI8(0, kind.ordinal.toByte())
        putI8(1, cause.ordinal.toByte())
        putI8(2, action.ordinal.toByte())
        putI8(3, 0)
        putI32(4, sectionX)
        putI32(8, sectionY)
        putI32(12, sectionZ)
        putI32(16, count)
        putI32(20, causedById)
    }

    fun logKindIsSection(v: MemorySegment): Boolean = v.byteSize() == LOG_KIND_SECTION_BYTES.toLong()
}
