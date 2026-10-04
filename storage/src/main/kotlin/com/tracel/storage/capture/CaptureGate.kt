package com.tracel.storage.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind

/**
 * What a listener actually calls. Wraps [CaptureRing] in the five shapes real capture code has,
 * so no listener has to think about claims, slots, or publication order.
 *
 * A full ring does not lose an event: it waits outside the ring, in memory, until the drain has caught up.
 * Every one of these returns `false` when the event was dropped, which happens when that waiting room is
 * full too, or interning is saturated. A caller that wants to know can look; a caller that does not
 * is correct to ignore it, because [CaptureRing.dropped] is counting either way.
 */
class CaptureGate(private val ring: CaptureRing) {
    val dropped: Long get() = ring.dropped

    /** A quantity of one item key moving between two holders. The whole hot path, in one call. */
    fun move(
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long,
        itemKey: ItemKey,
        from: HolderId,
        to: HolderId,
        quantity: Long,
    ): Boolean {
        val itemKeyId = ring.itemKeyId(itemKey)
        val fromId = ring.holderId(from)
        val toId = ring.holderId(to)
        val causedById = causedBy?.let(ring::holderId) ?: 0
        if (causedBy != null && causedById == 0) return false
        if (itemKeyId == 0 || fromId == 0 || toId == 0) return false

        val claim = ring.begin(cause, causedById, epochMillis, 2)
        if (claim == CaptureRing.REJECTED) {
            return ring.overflow(
                RingEvent.Items(
                    cause.ordinal, causedById, epochMillis,
                    intArrayOf(fromId, toId), intArrayOf(itemKeyId, itemKeyId), longArrayOf(-quantity, quantity),
                )
            )
        }
        ring.delta(claim, 0, fromId, itemKeyId, -quantity)
        ring.delta(claim, 1, toId, itemKeyId, quantity)
        ring.commit(claim, 2)
        return true
    }

    /** One unbalanced delta — a mint or a burn the balancer will pair against a source or a sink. */
    fun single(
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long,
        itemKey: ItemKey,
        holder: HolderId,
        delta: Long,
    ): Boolean {
        val itemKeyId = ring.itemKeyId(itemKey)
        val holderId = ring.holderId(holder)
        val causedById = causedBy?.let(ring::holderId) ?: 0
        if (causedBy != null && causedById == 0) return false
        if (itemKeyId == 0 || holderId == 0) return false

        val claim = ring.begin(cause, causedById, epochMillis, 1)
        if (claim == CaptureRing.REJECTED) {
            return ring.overflow(
                RingEvent.Items(
                    cause.ordinal, causedById, epochMillis,
                    intArrayOf(holderId), intArrayOf(itemKeyId), longArrayOf(delta),
                )
            )
        }
        ring.delta(claim, 0, holderId, itemKeyId, delta)
        ring.commit(claim, 1)
        return true
    }

    /** A diff's worth of deltas. The list is the caller's; nothing here keeps a reference to it. */
    fun many(cause: CauseKind, causedBy: HolderId?, epochMillis: Long, deltas: List<InventoryDelta>): Boolean {
        if (deltas.isEmpty()) return true
        if (deltas.size > CaptureRing.MAX_DELTAS) return false

        val causedById = causedBy?.let(ring::holderId) ?: 0
        if (causedBy != null && causedById == 0) return false
        val claim = ring.begin(cause, causedById, epochMillis, deltas.size)
        if (claim == CaptureRing.REJECTED) return ring.overflow(overflowedItems(cause, causedById, epochMillis, deltas))
        for (i in deltas.indices) {
            val delta = deltas[i]
            val holderId = runCatching { ring.holderId(delta.holder) }.getOrDefault(0)
            val itemKeyId = runCatching { ring.itemKeyId(delta.itemKey) }.getOrDefault(0)
            if (holderId == 0 || itemKeyId == 0) {
                // Publish it anyway: an event with an unresolvable id is dropped on the drain
                // side, and abandoning a claim would leave a hole the consumer would wait on
                // forever.
                ring.delta(claim, i, 0, 0, 0)
            } else {
                ring.delta(claim, i, holderId, itemKeyId, delta.delta)
            }
        }
        ring.commit(claim, deltas.size)
        return true
    }

    /**
     * Block edits that happened together — the world log's hot path.
     *
     * Only takes edits whose shapes carry no extras, because a sign's text is variable-length and
     * a ring slot is 24 bytes. Returns `false` for anything else so the caller knows to go the
     * slow way round rather than quietly losing it.
     */
    fun blocks(
        cause: CauseKind,
        action: ActionKind,
        causedBy: HolderId?,
        epochMillis: Long,
        world: WorldId,
        edits: List<BlockEdit>,
    ): Boolean {
        if (edits.isEmpty()) return true
        if (edits.size > CaptureRing.MAX_DELTAS) return false
        if (edits.any { it.before.extras != null || it.after.extras != null }) return false

        val worldId = ring.worldId(world)
        val causedById = causedBy?.let(ring::holderId) ?: 0
        if (causedBy != null && causedById == 0) return false
        if (worldId == 0) return false

        val befores = IntArray(edits.size)
        val afters = IntArray(edits.size)
        for (i in edits.indices) {
            befores[i] = ring.blockDataId(edits[i].before.data)
            afters[i] = ring.blockDataId(edits[i].after.data)
            if (befores[i] == 0 || afters[i] == 0) return false
        }
        val claim = ring.beginWorld(cause, action, causedById, epochMillis, worldId, edits.size)
        if (claim == CaptureRing.REJECTED) {
            val coordinates = IntArray(edits.size * 3)
            for (i in edits.indices) {
                coordinates[i * 3] = edits[i].at.x
                coordinates[i * 3 + 1] = edits[i].at.y
                coordinates[i * 3 + 2] = edits[i].at.z
            }
            return ring.overflow(
                RingEvent.World(cause.ordinal, causedById, epochMillis, action.ordinal, worldId, coordinates, befores, afters)
            )
        }
        for (i in edits.indices) {
            val edit = edits[i]
            ring.block(claim, i, edit.at.x, edit.at.y, edit.at.z, befores[i], afters[i])
        }
        ring.commit(claim, edits.size)
        return true
    }

    /** Block edits the ring cannot carry. */
    fun parkedBlocks(edits: BlockEdits): Boolean = ring.park(edits.cause, edits.epochMillis, edits)

    /** Item deltas that carry a place. */
    fun parkedDeltas(placed: PlacedDeltas): Boolean = ring.park(placed.cause, placed.epochMillis, placed)

    /** Everything [from] held went to [to] — see [CaptureRing.release]. */
    fun release(cause: CauseKind, causedBy: HolderId?, epochMillis: Long, from: HolderId, to: HolderId): Boolean {
        val fromId = ring.holderId(from)
        val toId = ring.holderId(to)
        val causedById = causedBy?.let(ring::holderId) ?: 0
        if (causedBy != null && causedById == 0) return false
        if (fromId == 0 || toId == 0) return false
        if (ring.release(cause, causedById, epochMillis, fromId, toId)) return true
        return ring.overflow(RingEvent.Release(cause.ordinal, causedById, epochMillis, fromId, toId))
    }

    private fun overflowedItems(
        cause: CauseKind,
        causedById: Int,
        epochMillis: Long,
        deltas: List<InventoryDelta>,
    ): RingEvent.Items {
        val holders = IntArray(deltas.size)
        val itemKeys = IntArray(deltas.size)
        val amounts = LongArray(deltas.size)
        for (i in deltas.indices) {
            val holderId = runCatching { ring.holderId(deltas[i].holder) }.getOrDefault(0)
            val itemKeyId = runCatching { ring.itemKeyId(deltas[i].itemKey) }.getOrDefault(0)
            if (holderId == 0 || itemKeyId == 0) continue
            holders[i] = holderId
            itemKeys[i] = itemKeyId
            amounts[i] = deltas[i].delta
        }
        return RingEvent.Items(cause.ordinal, causedById, epochMillis, holders, itemKeys, amounts)
    }
}
