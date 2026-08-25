package com.tracel.storage.codec

import com.tracel.storage.ffm.Bytes.i16
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI16
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
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

    // Readers

    /** `fromHolderId` of a "RELEASE" slot (bytes 16–19). */
    fun releaseFrom(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 16)

    /** `toHolderId` of a "RELEASE" slot (bytes 20–23). */
    fun releaseTo(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 20)

    /** Discriminator ("HEADER" / "DELTA" / "RELEASE"). */
    fun type(slots: MemorySegment, offset: Long): Byte = slots.i8(offset)

    /** Cause byte (valid for "HEADER" and "RELEASE"). */
    fun cause(slots: MemorySegment, offset: Long): Int = slots.i8(offset + 1).toInt()

    /**
     * Number of "DELTA" slots that follow this "HEADER".
     * Masked to 16 bits because the underlying store is unsigned in spirit.
     */
    fun deltaCount(slots: MemorySegment, offset: Long): Int = slots.i16(offset + 2).toInt() and 0xFFFF

    /** `causedByHolderId` ("HEADER" / "RELEASE"). */
    fun causedBy(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 4)

    /** `epochMillis` ("HEADER" / "RELEASE"). */
    fun epochMillis(slots: MemorySegment, offset: Long): Long = slots.i64(offset + 8)

    /** `holderId` of a "DELTA" slot. */
    fun holderId(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 4)

    /** `itemKeyId` of a "DELTA" slot (only the low 4 bytes are meaningful). */
    fun itemKeyId(slots: MemorySegment, offset: Long): Int = slots.i32(offset + 8)

    /** Signed quantity change of a "DELTA" slot. */
    fun delta(slots: MemorySegment, offset: Long): Long = slots.i64(offset + 16)
}
