package com.tracel.storage.codec

import com.tracel.storage.ffm.Bytes.i16
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI16
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.model.world.ActionKind
import java.lang.foreign.MemorySegment

/**
 * What a region thread writes into a ring slot: 24 bytes of primitives.
 *
 * A captured event is one "HEADER" followed by its "DELTAs", claimed as one contiguous run
 * so a drain never sees half an event. "RELEASE" is a single slot. Everything here is a
 * store into an already-mapped segment.
 *
 * `causedByHolderId` of 0 means nobody.
 *
 * "RELEASE" is "this holder stopped existing, everything it had went there": a despawned
 * ground item, a merged stack, a broken block. The region thread has no fucking business
 * knowing what the holder held — that is a ledger read — so it names the two holders and lets
 * the storage thread work out the quantities.
 *
 * | Bytes | Header                      | Delta                       | Release                     |
 * |------:|-----------------------------|-----------------------------|-----------------------------|
 * | 0     | type = 1 (u8)               | type = 2 (u8)               | type = 3 (u8)               |
 * | 1     | cause (u8)                  | flags (u8)                  | cause (u8)                  |
 * | 2–3   | deltaCount (u16)            | reserved (u16)              | reserved (u16)              |
 * | 4–7   | causedByHolderId (u32)      | holderId (u32)              | causedByHolderId (u32)      |
 * | 8–15  | epochMillis (i64)           | itemKeyId (u32) + pad       | epochMillis (i64)           |
 * | 16–23 | reserved (i64)              | delta (i64)                 | from (u32), to (u32)        |
 *
 * A world change is the same shape with different cargo: one "WORLD_HEADER" and one
 * "WORLD_BLOCK" per coordinate it touched, because an explosion edits forty blocks for one
 * reason and should not pay for forty headers.
 *
 * | Bytes | World header                | World block                 |
 * |------:|-----------------------------|-----------------------------|
 * | 0     | type = 4 (u8)               | type = 5 (u8)               |
 * | 1     | cause (u8)                  | reserved (u8)               |
 * | 2     | action (u8)                 | reserved (u16)              |
 * | 3     | reserved (u8)               |                             |
 * | 4–7   | causedByHolderId (u32)      | x (i32)                     |
 * | 8–15  | epochMillis (i64)           | y (i32), z (i32)            |
 * | 16–23 | worldId (u32), count (u16)  | beforeDataId, afterDataId   |
 *
 * Only blocks with no block entity come this way. Anything carrying a sign's text or a
 * spawner's settings is variable-length, does not fit 24 bytes, and takes the slow path.
 */
object CaptureSlot {
    /** Slot type: start of an event. Must be followed by exactly `deltaCount` "DELTA" slots. */
    const val HEADER: Byte = 1

    /** Slot type: one quantity change for a concrete (`holderId`, `itemKeyId`) pair. */
    const val DELTA: Byte = 2

    /**
     * Slot type: a holder has completely ceased to exist (despawn / merge / break).
     * All of its content must be moved from `from` to `to`.
     * The storage thread is responsible for looking up the actual quantities.
     */
    const val RELEASE: Byte = 3

    /** Slot type: start of a world change. Must be followed by exactly `count` "WORLD_BLOCK" slots. */
    const val WORLD_HEADER: Byte = 4

    /** Slot type: one coordinate going from one block state to another. */
    const val WORLD_BLOCK: Byte = 5

    /**
     * Writes a "HEADER" slot.
     *
     * @param slots the mapped ring segment
     * @param offset byte offset of this slot inside the segment
     * @param cause reason code (domain-specific, stored as u8)
     * @param deltaCount how many "DELTA" slots immediately follow this header
     * @param causedByHolderId holder that caused the event (0 = nobody)
     * @param epochMillis wall-clock timestamp of the capture
     */
    fun writeHeader(
        slots: MemorySegment,
        offset: Long,
        cause: Int,
        deltaCount: Int,
        causedByHolderId: Int,
        epochMillis: Long,
    ) {
        slots.putI8(offset, HEADER)
        slots.putI8(offset + 1, cause.toByte())
        slots.putI16(offset + 2, deltaCount.toShort())
        slots.putI32(offset + 4, causedByHolderId)
        slots.putI64(offset + 8, epochMillis)
        slots.putI64(offset + 16, 0L)                     // Reserved
    }

    /**
     * Writes a single "DELTA" slot.
     *
     * `itemKeyId` occupies only the first 4 bytes of the 8-byte field that
     * "HEADER" / "RELEASE" use for `epochMillis`. The remaining 4 bytes are
     * padding and are currently forced to zero.
     *
     * @param holderId the holder whose quantity is changing
     * @param itemKeyId interned key of the item
     * @param delta signed quantity change (can be negative)
     */
    fun writeDelta(slots: MemorySegment, offset: Long, holderId: Int, itemKeyId: Int, delta: Long) {
        slots.putI8(offset, DELTA)
        slots.putI8(offset + 1, 0)             // Flags
        slots.putI16(offset + 2, 0)            // Reserved
        slots.putI32(offset + 4, holderId)
        slots.putI32(offset + 8, itemKeyId)    // Low 4 bytes of the 8-byte slot
        slots.putI64(offset + 16, delta)       // High 4 bytes of the 8-byte field stay zero
    }

    /**
     * Writes a "RELEASE" slot.
     *
     * The region thread only supplies the two holder ids; the storage thread
     * is expected to read the ledger and transfer every item that belonged
     * to `fromHolderId` into `toHolderId`.
     *
     * @param cause reason the holder disappeared
     * @param causedByHolderId who / what triggered the release (0 = nobody)
     * @param epochMillis wall-clock timestamp
     * @param fromHolderId holder that is vanishing
     * @param toHolderId holder that receives everything
     */
    fun writeRelease(
        slots: MemorySegment,
        offset: Long,
        cause: Int,
        causedByHolderId: Int,
        epochMillis: Long,
        fromHolderId: Int,
        toHolderId: Int,
    ) {
        slots.putI8(offset, RELEASE)
        slots.putI8(offset + 1, cause.toByte())
        slots.putI16(offset + 2, 0)                   // Reserved
        slots.putI32(offset + 4, causedByHolderId)
        slots.putI64(offset + 8, epochMillis)
        slots.putI32(offset + 16, fromHolderId)
        slots.putI32(offset + 20, toHolderId)
    }

    /**
     * Writes a "WORLD_HEADER" slot.
     *
     * @param action what the change did, as [ActionKind]'s ordinal
     * @param count how many "WORLD_BLOCK" slots immediately follow
     * @param worldId interned world — one per event, since nothing edits two worlds at once
     */
    fun writeWorldHeader(
        slots: MemorySegment,
        offset: Long,
        cause: Int,
        action: Int,
        count: Int,
        causedByHolderId: Int,
        epochMillis: Long,
        worldId: Int,
    ) {
        slots.putI8(offset, WORLD_HEADER)
        slots.putI8(offset + 1, cause.toByte())
        slots.putI8(offset + 2, action.toByte())
        slots.putI8(offset + 3, 0)                    // Reserved
        slots.putI32(offset + 4, causedByHolderId)
        slots.putI64(offset + 8, epochMillis)
        slots.putI32(offset + 16, worldId)
        slots.putI16(offset + 20, count.toShort())
        slots.putI16(offset + 22, 0)                  // Reserved
    }

    /** Writes a "WORLD_BLOCK" slot: one coordinate, the state it left and the state it took. */
    fun writeWorldBlock(
        slots: MemorySegment,
        offset: Long,
        x: Int,
        y: Int,
        z: Int,
        beforeDataId: Int,
        afterDataId: Int,
    ) {
        slots.putI8(offset, WORLD_BLOCK)
        slots.putI8(offset + 1, 0)                 // Flags
        slots.putI16(offset + 2, 0)                // Reserved
        slots.putI32(offset + 4, x)
        slots.putI32(offset + 8, y)
        slots.putI32(offset + 12, z)
        slots.putI32(offset + 16, beforeDataId)
        slots.putI32(offset + 20, afterDataId)
    }

    // Readers

    fun releaseFrom(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 16)
    fun releaseTo(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 20)

    fun type(slots: MemorySegment, offset: Long): Byte = slots.i8(offset)

    fun cause(slots: MemorySegment, offset: Long): Int = slots.i8(offset + 1).toInt()

    fun deltaCount(slots: MemorySegment, offset: Long): Int = slots.i16(offset + 2).toInt() and 0xFFFF

    fun causedBy(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 4)

    fun epochMillis(slots: MemorySegment, offset: Long): Long = slots.i64(offset + 8)

    fun holderId(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 4)

    fun itemKeyId(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 8)

    fun delta(slots: MemorySegment, offset: Long): Long = slots.i64(offset + 16)

    fun action(slots: MemorySegment, offset: Long): Int = slots.i8(offset + 2).toInt()

    fun worldId(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 16)
    fun worldCount(slots: MemorySegment, offset: Long): Int = slots.i16(offset + 20).toInt() and 0xFFFF

    fun blockX(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 4)
    fun blockY(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 8)
    fun blockZ(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 12)

    fun blockBefore(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 16)
    fun blockAfter(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 20)
}
