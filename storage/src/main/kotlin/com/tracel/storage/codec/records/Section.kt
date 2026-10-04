package com.tracel.storage.codec.records

import com.tracel.annotations.CauseKind
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

class SectionExtras(val index: Int, val before: ByteArray, val after: ByteArray)

object Section {
    const val SECTION_POSITIONS = 4096
    private const val SECTION_BITMAP_BYTES = SECTION_POSITIONS / 8
    private const val SECTION_BITMAP_FROM = SECTION_BITMAP_BYTES / 2
    private const val WORLD_SECTION_TAIL = World.WCHG_HEADER_BYTES + 8
    internal const val STRUCT_SECTION_TAIL = 17
    internal const val STRUCT_SECTION: Byte = 3
    private const val SECTION_FLAG_BITMAP = 1
    private const val SECTION_FLAG_WIDE_INDEX = 2
    private const val SECTION_TAIL_BYTES = 24

    fun sectionDelta(
        action: ActionKind,
        cause: CauseKind,
        causedByHolderId: Int,
        worldId: Int,
        sectionX: Int,
        sectionY: Int,
        sectionZ: Int,
        epochMillis: Long,
        baseSeq: Long,
        positions: IntArray,
        count: Int,
        before: IntArray,
        after: IntArray,
        extras: List<SectionExtras> = emptyList(),
    ): ByteArray = packSection(WORLD_SECTION_TAIL, positions, count, before, after, extras) {
        writeWchgHeader(
            World.CHANGE_SECTION, action, cause, causedByHolderId, worldId,
            sectionX shl 4, sectionY shl 4, sectionZ shl 4, epochMillis,
        )
        putI64(32, baseSeq)
    }

    fun structureSection(
        worldId: Int,
        sectionX: Int,
        sectionY: Int,
        sectionZ: Int,
        positions: IntArray,
        count: Int,
        target: IntArray,
        expected: IntArray,
        extras: List<SectionExtras>,
    ): ByteArray = packSection(STRUCT_SECTION_TAIL, positions, count, target, expected, extras) {
        putI8(0, STRUCT_SECTION)
        putI32(1, worldId)
        putI32(5, sectionX shl 4)
        putI32(9, sectionY shl 4)
        putI32(13, sectionZ shl 4)
    }

    private inline fun packSection(
        tailAt: Int,
        positions: IntArray,
        count: Int,
        before: IntArray,
        after: IntArray,
        extras: List<SectionExtras>,
        head: MemorySegment.(Int) -> Unit,
    ): ByteArray {
        require(count > 0) { "a packed section with no positions says nothing" }
        val palette = paletteOf(before, after, count)
        val wide = palette.size > 256
        val bitmap = count >= SECTION_BITMAP_FROM
        val indexBytes = if (wide) 2 else 1

        val positionsAt = tailAt + SECTION_TAIL_BYTES
        val positionBytes = if (bitmap) SECTION_BITMAP_BYTES else count * 2
        val paletteAt = positionsAt + positionBytes
        val indicesAt = paletteAt + palette.size * 4
        val extrasAt = indicesAt + count * indexBytes * 2
        var extrasBytes = 4
        for (entry in extras) extrasBytes += 8 + entry.before.size + entry.after.size

        return recordBytes(extrasAt + extrasBytes) {
            head(paletteAt)
            putI32(tailAt.toLong(), count)
            putI8(
                tailAt + 4L,
                ((if (bitmap) SECTION_FLAG_BITMAP else 0) or (if (wide) SECTION_FLAG_WIDE_INDEX else 0)).toByte(),
            )
            putI16(tailAt + 5L, palette.size.toShort())
            putI32(tailAt + 8L, positionsAt)
            putI32(tailAt + 12L, paletteAt)
            putI32(tailAt + 16L, indicesAt)
            putI32(tailAt + 20L, extrasAt)

            if (bitmap) {
                for (i in 0 until count) {
                    val at = positions[i]
                    val byteAt = positionsAt + (at shr 3)
                    putI8(byteAt.toLong(), (i8(byteAt.toLong()).toInt() or (1 shl (at and 7))).toByte())
                }
            } else {
                for (i in 0 until count) putI16((positionsAt + i * 2).toLong(), positions[i].toShort())
            }

            for (i in palette.indices) putI32((paletteAt + i * 4).toLong(), palette[i])

            val slot = HashMap<Int, Int>(palette.size * 2)
            for (i in palette.indices) slot[palette[i]] = i
            for (i in 0 until count) {
                val b = slot.getValue(before[i])
                val a = slot.getValue(after[i])
                if (wide) {
                    putI16((indicesAt + i * 2).toLong(), b.toShort())
                    putI16((indicesAt + count * 2 + i * 2).toLong(), a.toShort())
                } else {
                    putI8((indicesAt + i).toLong(), b.toByte())
                    putI8((indicesAt + count + i).toLong(), a.toByte())
                }
            }

            putI32(extrasAt.toLong(), extras.size)
            var at = extrasAt + 4L
            for (entry in extras) {
                putI32(at, entry.index)
                putI16(at + 4, entry.before.size.toShort())
                putI16(at + 6, entry.after.size.toShort())
                writeBytes(at + 8, entry.before)
                writeBytes(at + 8 + entry.before.size, entry.after)
                at += 8 + entry.before.size + entry.after.size
            }
        }
    }

    private fun paletteOf(before: IntArray, after: IntArray, count: Int): IntArray {
        val seen = LinkedHashSet<Int>()
        for (i in 0 until count) {
            seen += before[i]
            seen += after[i]
        }
        return seen.toIntArray()
    }

    fun sectionBaseSeq(v: MemorySegment): Long = v.i64(World.WCHG_HEADER_BYTES.toLong())

    fun worldSectionTail(): Int = WORLD_SECTION_TAIL

    fun structSectionTail(): Int = STRUCT_SECTION_TAIL

    fun sectionCount(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): Int = v.i32(tailAt.toLong())

    fun sectionPaletteSize(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): Int =
        v.i16(tailAt + 5L).toInt() and 0xFFFF

    private fun sectionFlags(v: MemorySegment, tailAt: Int): Int = v.i8(tailAt + 4L).toInt()
    private fun positionsOffset(v: MemorySegment, tailAt: Int): Long = v.i32(tailAt + 8L).toLong()
    private fun paletteOffset(v: MemorySegment, tailAt: Int): Long = v.i32(tailAt + 12L).toLong()
    private fun sectionIndicesAt(v: MemorySegment, tailAt: Int): Long = v.i32(tailAt + 16L).toLong()
    private fun sectionExtrasAt(v: MemorySegment, tailAt: Int): Long = v.i32(tailAt + 20L).toLong()

    fun sectionPaletteAt(v: MemorySegment, slot: Int, tailAt: Int = WORLD_SECTION_TAIL): Int =
        v.i32(paletteOffset(v, tailAt) + slot * 4L)

    fun sectionBefore(v: MemorySegment, index: Int, tailAt: Int = WORLD_SECTION_TAIL): Int =
        sectionPaletteAt(v, sectionSlot(v, index, after = false, tailAt = tailAt), tailAt)

    fun sectionAfter(v: MemorySegment, index: Int, tailAt: Int = WORLD_SECTION_TAIL): Int =
        sectionPaletteAt(v, sectionSlot(v, index, after = true, tailAt = tailAt), tailAt)

    private fun sectionSlot(v: MemorySegment, index: Int, after: Boolean, tailAt: Int): Int {
        val count = sectionCount(v, tailAt)
        val at = sectionIndicesAt(v, tailAt)
        return if (sectionFlags(v, tailAt) and SECTION_FLAG_WIDE_INDEX != 0) {
            (v.i16(at + (if (after) count + index else index) * 2L).toInt() and 0xFFFF)
        } else {
            v.i8(at + (if (after) count + index else index).toLong()).toInt() and 0xFF
        }
    }

    fun sectionSlots(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): IntArray {
        val count = sectionCount(v, tailAt)
        val at = sectionIndicesAt(v, tailAt)
        val out = IntArray(count * 2)
        if (sectionFlags(v, tailAt) and SECTION_FLAG_WIDE_INDEX != 0) {
            for (i in out.indices) out[i] = v.i16(at + i * 2L).toInt() and 0xFFFF
        } else {
            for (i in out.indices) out[i] = v.i8(at + i).toInt() and 0xFF
        }
        return out
    }

    fun sectionPalette(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): IntArray {
        val size = sectionPaletteSize(v, tailAt)
        val at = paletteOffset(v, tailAt)
        return IntArray(size) { v.i32(at + it * 4L) }
    }

    fun sectionExtrasAll(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): Array<SectionExtras?>? {
        var at = sectionExtrasAt(v, tailAt)
        val entries = v.i32(at)
        if (entries == 0) return null
        at += 4
        val out = arrayOfNulls<SectionExtras>(sectionCount(v, tailAt))
        repeat(entries) {
            val which = v.i32(at)
            val beforeLength = v.i16(at + 4).toInt() and 0xFFFF
            val afterLength = v.i16(at + 6).toInt() and 0xFFFF
            if (which in out.indices && out[which] == null) {
                out[which] = SectionExtras(
                    which,
                    v.readBytes(at + 8, beforeLength),
                    v.readBytes(at + 8 + beforeLength, afterLength),
                )
            }
            at += 8 + beforeLength + afterLength
        }
        return out
    }

    inline fun forEachSectionPosition(
        v: MemorySegment,
        tailAt: Int = worldSectionTail(),
        action: (index: Int, packed: Int) -> Unit,
    ) {
        val count = sectionCount(v, tailAt)
        val at = sectionPositionsAt(v, tailAt)
        if (!sectionIsBitmap(v, tailAt)) {
            for (i in 0 until count) action(i, v.i16(at + i * 2L).toInt() and 0xFFFF)
            return
        }
        var index = 0
        var byteAt = 0
        while (index < count && byteAt < sectionBitmapBytes()) {
            var bits = v.i8(at + byteAt).toInt() and 0xFF
            while (bits != 0) {
                val bit = Integer.numberOfTrailingZeros(bits)
                action(index++, (byteAt shl 3) or bit)
                bits = bits and (bits - 1)
            }
            byteAt++
        }
    }

    fun sectionIndexOf(v: MemorySegment, packed: Int, tailAt: Int = WORLD_SECTION_TAIL): Int {
        val count = sectionCount(v, tailAt)
        val at = sectionPositionsAt(v, tailAt)
        if (!sectionIsBitmap(v, tailAt)) {
            var low = 0
            var high = count - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val here = v.i16(at + mid * 2L).toInt() and 0xFFFF
                when {
                    here < packed -> low = mid + 1
                    here > packed -> high = mid - 1
                    else -> return mid
                }
            }
            return -1
        }
        val byteAt = packed shr 3
        if (v.i8(at + byteAt).toInt() and (1 shl (packed and 7)) == 0) return -1
        var index = 0
        for (b in 0 until byteAt) index += Integer.bitCount(v.i8(at + b).toInt() and 0xFF)
        return index + Integer.bitCount((v.i8(at + byteAt).toInt() and 0xFF) and ((1 shl (packed and 7)) - 1))
    }

    fun sectionExtras(v: MemorySegment, index: Int, tailAt: Int = WORLD_SECTION_TAIL): SectionExtras? {
        var at = sectionExtrasAt(v, tailAt)
        val entries = v.i32(at)
        at += 4
        repeat(entries) {
            val which = v.i32(at)
            val beforeLength = v.i16(at + 4).toInt() and 0xFFFF
            val afterLength = v.i16(at + 6).toInt() and 0xFFFF
            if (which == index) {
                return SectionExtras(
                    index,
                    v.readBytes(at + 8, beforeLength),
                    v.readBytes(at + 8 + beforeLength, afterLength),
                )
            }
            at += 8 + beforeLength + afterLength
        }
        return null
    }

    fun sectionIsBitmap(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): Boolean =
        sectionFlags(v, tailAt) and SECTION_FLAG_BITMAP != 0

    fun sectionPositionsAt(v: MemorySegment, tailAt: Int = WORLD_SECTION_TAIL): Long =
        positionsOffset(v, tailAt)

    fun sectionBitmapBytes(): Int = SECTION_BITMAP_BYTES

    fun packSectionPosition(x: Int, y: Int, z: Int): Int =
        ((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)

    fun sectionPositionX(packed: Int): Int = packed and 15
    fun sectionPositionZ(packed: Int): Int = (packed shr 4) and 15
    fun sectionPositionY(packed: Int): Int = (packed shr 8) and 15
}
