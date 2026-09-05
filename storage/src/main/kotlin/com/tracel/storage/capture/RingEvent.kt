package com.tracel.storage.capture

import com.tracel.storage.codec.CaptureSlot

/**
 * One published ring event, copied off the mmap. Slots will go back immediately.
 *
 * IDs are intern numbers. [Drainer] resolves them on the storage thread.
 */
internal sealed interface RingEvent {
    val cause: Int
    val causedBy: Int
    val epochMillis: Long

    class Items(
        override val cause: Int,
        override val causedBy: Int,
        override val epochMillis: Long,
        val holders: IntArray,
        val itemKeys: IntArray,
        val amounts: LongArray,
    ) : RingEvent

    class Release(
        override val cause: Int,
        override val causedBy: Int,
        override val epochMillis: Long,
        val fromHolderId: Int,
        val toHolderId: Int,
    ) : RingEvent

    class World(
        override val cause: Int,
        override val causedBy: Int,
        override val epochMillis: Long,
        val action: Int,
        val worldId: Int,
        val coordinates: IntArray,
        val befores: IntArray,
        val afters: IntArray,
    ) : RingEvent
}

internal fun CaptureRing.collectPublished(maxBatch: Int): List<RingEvent> {
    val out = ArrayList<RingEvent>()
    var cursor = consumerCursor()
    val payload = payload

    while (out.size < maxBatch) {
        if (!isPublished(cursor)) break
        val at = payloadOffset(cursor)
        when (val type = CaptureSlot.type(payload, at)) {
            CaptureSlot.RELEASE -> {
                out += RingEvent.Release(
                    CaptureSlot.cause(payload, at),
                    CaptureSlot.causedBy(payload, at),
                    CaptureSlot.epochMillis(payload, at),
                    CaptureSlot.releaseFrom(payload, at),
                    CaptureSlot.releaseTo(payload, at),
                )
                cursor += 1
            }

            CaptureSlot.HEADER -> {
                val count = CaptureSlot.deltaCount(payload, at)
                val holders = IntArray(count)
                val itemKeys = IntArray(count)
                val amounts = LongArray(count)
                for (i in 0 until count) {
                    val slot = payloadOffset(cursor + 1 + i)
                    holders[i] = CaptureSlot.holderId(payload, slot)
                    itemKeys[i] = CaptureSlot.itemKeyId(payload, slot)
                    amounts[i] = CaptureSlot.delta(payload, slot)
                }
                out += RingEvent.Items(
                    CaptureSlot.cause(payload, at),
                    CaptureSlot.causedBy(payload, at),
                    CaptureSlot.epochMillis(payload, at),
                    holders,
                    itemKeys,
                    amounts,
                )
                cursor += 1L + count
            }

            CaptureSlot.WORLD_HEADER -> {
                val count = CaptureSlot.worldCount(payload, at)
                val coordinates = IntArray(count * 3)
                val befores = IntArray(count)
                val afters = IntArray(count)
                for (i in 0 until count) {
                    val slot = payloadOffset(cursor + 1 + i)
                    coordinates[i * 3] = CaptureSlot.blockX(payload, slot)
                    coordinates[i * 3 + 1] = CaptureSlot.blockY(payload, slot)
                    coordinates[i * 3 + 2] = CaptureSlot.blockZ(payload, slot)
                    befores[i] = CaptureSlot.blockBefore(payload, slot)
                    afters[i] = CaptureSlot.blockAfter(payload, slot)
                }
                out += RingEvent.World(
                    CaptureSlot.cause(payload, at),
                    CaptureSlot.causedBy(payload, at),
                    CaptureSlot.epochMillis(payload, at),
                    CaptureSlot.action(payload, at),
                    CaptureSlot.worldId(payload, at),
                    coordinates,
                    befores,
                    afters,
                )
                cursor += 1L + count
            }

            else -> error("ring slot $cursor holds type $type where an event was expected — the ring is corrupt")
        }
    }

    if (out.isNotEmpty()) releaseSlots(cursor)
    return out
}
