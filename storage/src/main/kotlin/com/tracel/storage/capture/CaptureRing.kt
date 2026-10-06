package com.tracel.storage.capture

import com.tracel.model.transaction.CauseKind
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.storage.codec.CaptureSlot
import com.tracel.storage.ffm.OffHeapRing
import com.tracel.storage.intern.Interning
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

private const val APPLIED_POLL_MILLIS = 10L

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
 * val claim = ring.begin(CauseKind.MACHINE, causedBy = 0, epochMillis, deltaCount = 2)
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
class CaptureRing(
    slots: Int,
    private val interning: Interning,
    overflowSlots: Int = slots * OVERFLOW_FACTOR,
) : AutoCloseable {
    private val ring = OffHeapRing(slots)

    private val overflowCapacity = overflowSlots.toLong()
    private val waiting = ConcurrentLinkedQueue<Queued>()
    private val overflowHeld = AtomicLong()
    private val overflowQueued = AtomicLong()
    private val overflowApplied = AtomicLong()
    private val lost = AtomicLong()

    private val parked = ConcurrentHashMap<Int, Any>()
    private val parkTokens = AtomicInteger()

    /** Events that found neither a free slot nor room to wait, and were lost. */
    val dropped: Long get() = lost.get() + interning.droppedForCapacity

    /** How many times an event found the ring full, whether or not it then found room to wait. */
    val ringFull: Long get() = ring.dropped

    /** Slots capture has claimed that the drain has not applied yet, and the slots' worth of events waiting outside. */
    val backlog: Long get() = ring.claimCursor() - ring.consumerCursor() + overflowHeld.get()

    /**
     * Waits, without draining anything itself, until every slot claimed so far has been applied.
     *
     * @return whether that happened within [timeoutMs]
     */
    suspend fun awaitApplied(timeoutMs: Long): Boolean {
        val upTo = ring.claimCursor()
        val queued = overflowQueued.get()
        val deadline = System.currentTimeMillis() + timeoutMs
        while ((ring.consumerCursor() < upTo || overflowApplied.get() < queued) && System.currentTimeMillis() < deadline) {
            delay(APPLIED_POLL_MILLIS.milliseconds)
        }
        return ring.consumerCursor() >= upTo && overflowApplied.get() >= queued
    }

    fun holderId(holder: HolderId): Int = interning.holderIdForCapture(holder)

    fun itemKeyId(itemKey: ItemKey): Int = interning.itemKeyIdForCapture(itemKey)

    fun worldId(world: WorldId): Int = interning.worldIdForCapture(world)

    fun blockDataId(blockData: BlockDataKey): Int = interning.blockDataIdForCapture(blockData)

    /** Claims `1 + deltaCount` contiguous slots, or [REJECTED] if the ring is full. Never waits. */
    fun begin(cause: CauseKind, causedByHolderId: Int, epochMillis: Long, deltaCount: Int): Long {
        require(deltaCount in 1..MAX_DELTAS) { "an event with $deltaCount deltas does not belong in a ring slot" }
        if (overflowHeld.get() > 0) return REJECTED
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
        if (overflowHeld.get() > 0) return REJECTED
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
        if (overflowHeld.get() > 0) return false
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
        val claim = if (overflowHeld.get() > 0) OffHeapRing.CLAIM_FAILED else ring.claim(1)
        if (claim == OffHeapRing.CLAIM_FAILED) {
            val weight = when (payload) {
                is BlockEdits -> payload.edits.size
                is PlacedDeltas -> payload.deltas.size
                else -> 0
            } + 1L
            val marker = RingEvent.Release(cause.ordinal, 0, epochMillis, PARKED, token)
            val queued = overflow(marker, weight)
            if (!queued) parked.remove(token)
            return queued
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

    /**
     * Holds [event] outside the ring, to be applied after everything in it. Never waits.
     *
     * @return `false` if what already waits is as much as may, and the event was lost and counted
     */
    internal fun overflow(event: RingEvent, weight: Long = weightOf(event)): Boolean {
        var held = overflowHeld.get()
        while (true) {
            if (held + weight > overflowCapacity) {
                lost.incrementAndGet()
                return false
            }
            if (overflowHeld.compareAndSet(held, held + weight)) break
            held = overflowHeld.get()
        }
        overflowQueued.incrementAndGet()
        waiting.add(Queued(event, weight))
        return true
    }

    // Storage thread only

    /** Whether events wait outside the ring, which new events then join, so that order holds. */
    internal fun hasOverflow(): Boolean = overflowHeld.get() > 0

    /**
     * The oldest events waiting outside the ring, if the ring itself is empty: they came after everything in it.
     * They stay where they are until [releaseOverflow], so a batch that never lands is read again.
     */
    internal fun takeOverflow(max: Int): TakenOverflow? {
        if (waiting.isEmpty() || ring.claimCursor() > ring.consumerCursor()) return null
        val events = ArrayList<RingEvent>()
        var weight = 0L
        for (queued in waiting) {
            if (events.size >= max) break
            events += queued.event
            weight += queued.weight
        }
        return if (events.isEmpty()) null else TakenOverflow(events, weight)
    }

    internal fun releaseOverflow(taken: TakenOverflow) {
        repeat(taken.events.size) { waiting.poll() }
        overflowHeld.addAndGet(-taken.weight)
        overflowApplied.addAndGet(taken.events.size.toLong())
    }

    internal fun overflowQueuedTotal(): Long = overflowQueued.get()

    internal fun overflowAppliedTotal(): Long = overflowApplied.get()

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

    internal class TakenOverflow(val events: List<RingEvent>, val weight: Long)

    private class Queued(val event: RingEvent, val weight: Long)

    companion object {
        const val REJECTED = -1L // It must not wait, ever
        const val MAX_DELTAS = 4095

        const val OVERFLOW_FACTOR = 16

        private fun weightOf(event: RingEvent): Long = 1L + when (event) {
            is RingEvent.Items -> event.holders.size
            is RingEvent.World -> event.befores.size
            is RingEvent.Release -> 0
        }

        internal const val PARKED = 0
    }
}
