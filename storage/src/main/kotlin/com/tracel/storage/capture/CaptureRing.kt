package com.tracel.storage.capture

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.storage.codec.CaptureSlot
import com.tracel.storage.ffm.OffHeapRing
import com.tracel.storage.intern.Interning

/**
 * 
 *
 * What a `Folia` region thread is allowed to do with storage: intern two ids, write 24 bytes,
 * return — and goodbye.
 *
 * ```
 * // Fetches the numeric ID for the chest from the interning cache
 * val chest = ring.holderId(chestHolder)
 *
 * // Fetches the numeric ID for the item (diamond) from the interning cache
 * val item  = ring.itemKeyId(diamond)
 *
 * // If the string / object cache is full and returns 0, exit immediately
 * if (chest == 0 || item == 0) return
 *
 * // Reserves space in the ring buffer for a header and 2 state deltas
 * val claim = ring.begin(CauseKind.HOPPER, causedBy = 0, epochMillis, deltaCount = 2)
 *
 * // If the ring buffer is full, exit immediately
 * if (claim == CaptureRing.REJECTED) return
 *
 * // Writes delta #1 directly to native memory
 * ring.delta(claim, 0, chest, item, -quantity)
 *
 * // Writes delta #2 directly to native memory
 * ring.delta(claim, 1, hopper, item, +quantity)
 *
 * // Push
 * ring.commit(claim, 2)
 * ```
 */
class CaptureRing(slots: Int, private val interning: Interning) : AutoCloseable {
    private val ring = OffHeapRing(slots)

    val dropped: Long get() = ring.dropped + interning.droppedForCapacity
    val capacity: Int get() = ring.capacitySlots

    fun holderId(holder: HolderId): Int = interning.holderIdForCapture(holder)

    fun itemKeyId(itemKey: ItemKey): Int = interning.itemKeyIdForCapture(itemKey)

    /** Claims `1 + deltaCount` contiguous slots, or [REJECTED] if the ring is full. Never waits. */
    fun begin(cause: CauseKind, causedByHolderId: Int, epochMillis: Long, deltaCount: Int): Long {
        require(deltaCount in 1..MAX_DELTAS) { "an event with $deltaCount deltas does not belong in a ring slot" }
        val claim = ring.claim(1 + deltaCount)
        if (claim == OffHeapRing.CLAIM_FAILED) return REJECTED
        CaptureSlot.writeHeader(
            ring.payload,
            ring.payloadOffset(claim),
            cause.ordinal,
            deltaCount,
            causedByHolderId,
            epochMillis,
        )
        return claim
    }

    /** Delta. */
    fun delta(claim: Long, index: Int, holderId: Int, itemKeyId: Int, delta: Long) {
        CaptureSlot.writeDelta(ring.payload, ring.payloadOffset(claim + 1 + index), holderId, itemKeyId, delta)
    }

    /** Enqueues "everything [fromHolderId] had went to [toHolderId]" in one slot. */
    fun release(
        cause: CauseKind,
        causedByHolderId: Int,
        epochMillis: Long,
        fromHolderId: Int,
        toHolderId: Int,
    ): Boolean {
        val claim = ring.claim(1)
        if (claim == OffHeapRing.CLAIM_FAILED) return false
        CaptureSlot.writeRelease(
            ring.payload,
            ring.payloadOffset(claim),
            cause.ordinal,
            causedByHolderId,
            epochMillis,
            fromHolderId,
            toHolderId,
        )
        ring.publish(claim)
        return true
    }

    /** Deltas first, header last: a published header is a promise that the whole event is there. */
    fun commit(claim: Long, deltaCount: Int) {
        for (i in deltaCount downTo 1) ring.publish(claim + i)
        ring.publish(claim)
    }

    // Storage thread only

    internal fun consumerCursor(): Long = ring.consumerCursor()

    internal fun isPublished(sequence: Long): Boolean = ring.isPublished(sequence)

    internal fun payloadOffset(sequence: Long): Long = ring.payloadOffset(sequence)

    internal val payload get() = ring.payload

    internal fun releaseSlots(upTo: Long) = ring.release(upTo)

    override fun close() {
        ring.close()
    }

    companion object {
        /** The ring had no room. The caller drops the event; it must not wait, ever. */
        const val REJECTED = -1L

        /** A single captured event's deltas. An explosion diff bigger than this is split by the caller. */
        const val MAX_DELTAS = 4095
    }
}
