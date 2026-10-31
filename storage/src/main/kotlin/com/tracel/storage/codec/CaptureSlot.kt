package com.tracel.storage.codec

import com.tracel.storage.ffm.Bytes.i8
import java.lang.foreign.MemorySegment

/**
 * What a region thread writes into a ring slot: 24 bytes of primitives, stored straight into an
 * already-mapped segment.
 */
object CaptureSlot {
    const val HEADER: Byte = 1
    const val DELTA: Byte = 2
    const val RELEASE: Byte = 3
    const val WORLD_HEADER: Byte = 4
    const val WORLD_BLOCK: Byte = 5

    object Header : Record() {
        val type = i8()
        val cause = i8()
        val deltaCount = u16()
        val causedBy = i32()
        val epochMillis = i64()
        internal val gap = pad(8)
    }

    object Delta : Record() {
        val type = i8()
        internal val gap = pad(3)
        val holderId = i32()
        val itemKeyId = i32()
        internal val gap2 = pad(4)
        val delta = i64()
    }

    object Release : Record() {
        val type = i8()
        val cause = i8()
        internal val gap = pad(2)
        val causedBy = i32()
        val epochMillis = i64()
        val from = i32()
        val to = i32()
    }

    object WorldHeader : Record() {
        val type = i8()
        val cause = i8()
        val action = i8()
        internal val gap = pad(1)
        val causedBy = i32()
        val epochMillis = i64()
        val worldId = i32()
        val count = u16()
        internal val gap2 = pad(2)
    }

    object WorldBlock : Record() {
        val type = i8()
        internal val gap = pad(3)
        val x = i32()
        val y = i32()
        val z = i32()
        val before = i32()
        val after = i32()
    }

    fun type(slots: MemorySegment, at: Long): Byte = slots.i8(at)

    // Field by field, no writer: the region thread allocates nothing

    fun writeHeader(slots: MemorySegment, at: Long, cause: Int, deltas: Int, causedBy: Int, millis: Long) = with(Header) {
        type[slots, at] = HEADER
        this.cause[slots, at] = cause.toByte()
        deltaCount[slots, at] = deltas
        this.causedBy[slots, at] = causedBy
        epochMillis[slots, at] = millis
        gap.clear(slots, at)
    }

    fun writeDelta(slots: MemorySegment, at: Long, holder: Int, itemKey: Int, amount: Long) = with(Delta) {
        type[slots, at] = DELTA
        gap.clear(slots, at)
        holderId[slots, at] = holder
        itemKeyId[slots, at] = itemKey
        gap2.clear(slots, at)
        delta[slots, at] = amount
    }

    fun writeRelease(slots: MemorySegment, at: Long, cause: Int, causedBy: Int, millis: Long, from: Int, to: Int) =
        with(Release) {
            type[slots, at] = RELEASE
            this.cause[slots, at] = cause.toByte()
            gap.clear(slots, at)
            this.causedBy[slots, at] = causedBy
            epochMillis[slots, at] = millis
            this.from[slots, at] = from
            this.to[slots, at] = to
        }

    fun writeWorldHeader(
        slots: MemorySegment, at: Long, cause: Int, action: Int, count: Int, causedBy: Int, millis: Long, worldId: Int,
    ) = with(WorldHeader) {
        type[slots, at] = WORLD_HEADER
        this.cause[slots, at] = cause.toByte()
        this.action[slots, at] = action.toByte()
        gap.clear(slots, at)
        this.causedBy[slots, at] = causedBy
        epochMillis[slots, at] = millis
        this.worldId[slots, at] = worldId
        this.count[slots, at] = count
        gap2.clear(slots, at)
    }

    fun writeWorldBlock(slots: MemorySegment, at: Long, x: Int, y: Int, z: Int, before: Int, after: Int) = with(WorldBlock) {
        type[slots, at] = WORLD_BLOCK
        gap.clear(slots, at)
        this.x[slots, at] = x
        this.y[slots, at] = y
        this.z[slots, at] = z
        this.before[slots, at] = before
        this.after[slots, at] = after
    }
}
