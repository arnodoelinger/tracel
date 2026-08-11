package com.tracel.model.flow

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * Invariant I1: no flow is ever allowed to create or lose mass on its own —
 * every unit that leaves [Flow.source] arrives at [Flow.destination].
 *
 * `Flow`'s shape already makes that structurally impossible to violate, so this is a
 * cheap regression guard (used by `@Balanced`) rather than something that meaningfully
 * fails at runtime — it groups by item key first, because summing deltas of different
 * items would let a `+5 diamond` and a `-5 dirt` cancel out and hide a real bug.
 */
public fun List<Flow>.isBalanced(): Boolean =
    groupBy { it.itemKey }.values.all { sameItem -> sameItem.netDeltaByHolder().values.sum() == 0L }

/** Net change per holder for one item key's worth of flows. */
public fun List<Flow>.netDeltaByHolder(): Map<HolderId, Long> {
    val net = LinkedHashMap<HolderId, Long>()
    for ((_, quantity, source, destination) in this) {
        net.merge(source, -quantity.raw, Long::plus)
        net.merge(destination, quantity.raw, Long::plus)
    }
    return net.filterValues { it != 0L }
}

/** All item keys touched by this transaction's flows. */
public fun List<Flow>.itemKeys(): Set<ItemKey> = mapTo(LinkedHashSet()) { it.itemKey }
