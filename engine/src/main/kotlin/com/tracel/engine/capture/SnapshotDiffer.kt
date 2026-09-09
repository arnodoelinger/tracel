package com.tracel.engine.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the last known contents of each holder `Tracel` watches, and turns "here's what it
 * looks like now" into the raw deltas [TransactionBalancer] needs.
 *
 * This is the mechanism the whole capture design leans on: diffing an inventory's contents,
 * never interpreting which click, drag, or hotbar swap produced them.
 */
public class SnapshotDiffer(
    private val baseline: suspend (HolderId) -> Map<ItemKey, Long> = { emptyMap() },
) {
    private data class Snapshot(val totals: Map<ItemKey, Long>, val seeded: Boolean)
    private val snapshots = ConcurrentHashMap<HolderId, Snapshot>()

    /**
     * Compares [current] against whatever [holder] looked like last time, returns the deltas, and
     * remembers [current] as the new baseline.
     *
     * "Last time" is [baseline] plus any pending [adjust]ments the first time this runs for a
     * holder.
     */
    public suspend fun diff(holder: HolderId, current: Map<ItemKey, Long>): List<InventoryDelta> {
        val snapshot = snapshots.put(holder, Snapshot(current, seeded = true))
        val fromGap = snapshot?.seeded != true
        val previous = when {
            snapshot == null -> baseline(holder)
            snapshot.seeded -> snapshot.totals
            else -> baseline(holder).merge(snapshot.totals)
        }

        return deltasBetween(holder, previous, current, fromGap)
    }

    /**
     * [diff] for a holder that already has a snapshot. Safe to call from a region thread.
     *
     * @return `null` when the holder has never been diffed, which is the one case that genuinely
     * needs a ledger read. A caller takes the slow path only then, and only once per holder.
     */
    public fun diffIfSeeded(holder: HolderId, current: Map<ItemKey, Long>): List<InventoryDelta>? {
        val snapshot = snapshots[holder]
        if (snapshot?.seeded != true) return null
        if (!snapshots.replace(holder, snapshot, Snapshot(current, seeded = true))) return null
        return deltasBetween(holder, snapshot.totals, current, fromGap = false)
    }

    /** Whether [holder] has a snapshot [diffIfSeeded] would accept. */
    public fun seeded(holder: HolderId): Boolean = snapshots[holder]?.seeded == true

    private fun deltasBetween(
        holder: HolderId,
        previous: Map<ItemKey, Long>,
        current: Map<ItemKey, Long>,
        fromGap: Boolean,
    ): List<InventoryDelta> {
        val touched = previous.keys + current.keys
        return touched.mapNotNull { key ->
            val delta = current.getOrDefault(key, 0L) - previous.getOrDefault(key, 0L)
            if (delta == 0L) null else InventoryDelta(holder, key, delta, fromGap)
        }
    }

    /**
     * Declares [current] to be [holder]'s contents without reporting a single delta — for when
     * `Tracel` changed the inventory itself and already knows what it did.
     *
     * Distinct from [diff] with the result discarded, which would consult [baseline] and
     * pointlessly reconcile against a gap that a rollback step just closed on purpose.
     */
    public fun rebaseline(holder: HolderId, current: Map<ItemKey, Long>) {
        snapshots[holder] = Snapshot(current, seeded = true)
    }

    /** Forgets [holder]'s snapshot for when a holder stops being watched (unloaded, deleted). */
    public fun forget(holder: HolderId) {
        snapshots.remove(holder)
    }

    /**
     * Nudges [holder]'s cached snapshot by [delta] units of [itemKey] for a capture that
     * already knows the exact change directly from event fields (a drop, a pickup) and never
     * calls [diff] itself for this holder.
     *
     * Never touches [baseline], because this runs on whatever thread the event did, and the baseline
     * may be a storage-bound read.
     *
     * On a holder with no snapshot yet the adjustment is simply booked as pending, and the next
     * [diff] folds it into the baseline it fetches.
     */
    public fun adjust(holder: HolderId, itemKey: ItemKey, delta: Long) {
        snapshots.compute(holder) { _, current ->
            val seeded = current?.seeded == true
            val base = current?.totals.orEmpty()
            val sum = base.getOrDefault(itemKey, 0L) + delta
            val updated = if (seeded) sum.coerceAtLeast(0L) else sum
            val totals = if (updated == 0L) base - itemKey else base + (itemKey to updated)
            Snapshot(totals, seeded)
        }
    }

    /** Forgets every snapshot. */
    public fun forgetAll() {
        snapshots.clear()
    }
}

private fun Map<ItemKey, Long>.merge(deltas: Map<ItemKey, Long>): Map<ItemKey, Long> {
    val merged = toMutableMap()
    for ((key, delta) in deltas) {
        val updated = merged.getOrDefault(key, 0L) + delta
        if (updated == 0L) merged -= key else merged[key] = updated
    }
    return merged
}
