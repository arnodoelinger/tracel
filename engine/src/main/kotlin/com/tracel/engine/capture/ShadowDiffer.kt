package com.tracel.engine.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the last known contents of each holder `Tracel` watches, and turns "here's what it
 * looks like now" into the raw deltas [com.tracel.engine.balance.TransactionBalancer] needs.
 *
 * This is the mechanism the whole capture design leans on: diffing an inventory's contents,
 * never interpreting which click, drag, or hotbar swap produced them.
 */
public class ShadowDiffer {
    private val snapshots = ConcurrentHashMap<HolderId, Map<ItemKey, Long>>()

    /**
     * Compares [current] against whatever [holder] looked like last time [diff] ran for it
     * (empty, the first time), returns the deltas, and remembers [current] as the new baseline.
     */
    public fun diff(holder: HolderId, current: Map<ItemKey, Long>): List<InventoryDelta> {
        val previous = snapshots.put(holder, current).orEmpty()
        val touched = previous.keys + current.keys
        return touched.mapNotNull { key ->
            val delta = current.getOrDefault(key, 0L) - previous.getOrDefault(key, 0L)
            if (delta == 0L) null else InventoryDelta(holder, key, delta)
        }
    }

    /** Forgets [holder]'s snapshot — for when a holder stops being watched (unloaded, deleted). */
    public fun forget(holder: HolderId) {
        snapshots.remove(holder)
    }
}
