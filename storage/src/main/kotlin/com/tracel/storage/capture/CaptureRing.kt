package com.tracel.storage.capture

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.storage.codec.CaptureSlot
import com.tracel.storage.ffm.OffHeapRing
import com.tracel.storage.intern.Interning
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    private val parked = ConcurrentHashMap<Int, Any>()
    private val parkTokens = AtomicInteger()

    val dropped: Long get() = ring.dropped + interning.droppedForCapacity

    fun holderId(holder: HolderId): Int = interning.holderIdForCapture(holder)

    fun itemKeyId(itemKey: ItemKey): Int = interning.itemKeyIdForCapture(itemKey)

    fun worldId(world: WorldId): Int = interning.worldIdForCapture(world)

    fun blockDataId(blockData: BlockDataKey): Int = interning.blockDataIdForCapture(blockData)

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

    /**
     * Claims `1 + count` contiguous slots for a world change, or [REJECTED] if the ring is full.
     *
     * One header for the whole event rather than one per block: an explosion is forty coordinates
     * changing for a single reason, at a single instant, in a single world.
     */
    fun beginWorld(
        cause: CauseKind,
        action: ActionKind,
        causedByHolderId: Int,
        epochMillis: Long,
        worldId: Int,
        count: Int,
    ): Long {
        require(count in 1..MAX_DELTAS) { "a world change touching $count blocks does not belong in a ring slot" }
        val claim = ring.claim(1 + count)
        if (claim == OffHeapRing.CLAIM_FAILED) return REJECTED
        CaptureSlot.writeWorldHeader(
            ring.payload,
            ring.payloadOffset(claim),
            cause.ordinal,
            action.ordinal,
            count,
            causedByHolderId,
            epochMillis,
            worldId,
        )
        return claim
    }

    /** One coordinate of a world change claimed by [beginWorld]. */
    fun block(claim: Long, index: Int, x: Int, y: Int, z: Int, beforeDataId: Int, afterDataId: Int) {
        CaptureSlot.writeWorldBlock(
            ring.payload,
            ring.payloadOffset(claim + 1 + index),
            x,
            y,
            z,
            beforeDataId,
            afterDataId
        )
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

    /** Holds [payload] off the ring and puts a one-slot marker in its place/ */
    fun park(cause: CauseKind, epochMillis: Long, payload: Any): Boolean {
        var token = parkTokens.incrementAndGet() and Int.MAX_VALUE
        if (token == 0) token = parkTokens.incrementAndGet() and Int.MAX_VALUE
        parked[token] = payload
        val claim = ring.claim(1)
        if (claim == OffHeapRing.CLAIM_FAILED) {
            parked.remove(token)
            return false
        }
        CaptureSlot.writeRelease(ring.payload, ring.payloadOffset(claim), cause.ordinal, 0, epochMillis, PARKED, token)
        ring.publish(claim)
        return true
    }

    /**
     * Payload first, header last: a published header is a promise that the whole event is there.
     *
     * Works for both event shapes — deltas and world blocks are the same run of slots behind the
     * same header, and publication order is the only thing that makes either safe to read.
     */
    fun commit(claim: Long, count: Int) {
        for (i in count downTo 1) ring.publish(claim + i)
        ring.publish(claim)
    }

    // Storage thread only

    internal fun consumerCursor(): Long = ring.consumerCursor()

    internal fun claimCursor(): Long = ring.claimCursor()

    internal fun isPublished(sequence: Long): Boolean = ring.isPublished(sequence)

    internal fun payloadOffset(sequence: Long): Long = ring.payloadOffset(sequence)

    internal val payload get() = ring.payload

    internal fun releaseSlots(upTo: Long) = ring.release(upTo)

    internal fun parked(token: Int): Any? = parked[token]

    internal fun forgetParked(tokens: Collection<Int>) {
        for (token in tokens) parked.remove(token)
    }

    override fun close() {
        ring.close()
    }

    companion object {
        const val REJECTED = -1L // It must not wait, ever
        const val MAX_DELTAS = 4095

        internal const val PARKED = 0
    }
}
