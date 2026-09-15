package com.tracel.plugin.listener.support

import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/**
 * Mint unseen, then move what left, then burn believed leftovers.
 *
 * A move of unrecorded stock is `insufficient balance` (swallowed at FINE) and the floor item
 * has no lot — same as never recording it. Mint is the fact the block existed, not a fudge:
 * natural terrain otherwise stays an untraceable drop.
 */

val WORLDGEN_SOURCE: HolderId.Source = HolderId.Source(SourceKind.WORLDGEN)

val DESTROYED_SINK: HolderId.Sink = HolderId.Sink(SinkKind.UNATTRIBUTED)

/**
 * Vanilla-claimed drops vs believed stock: surplus is mint-then-move; unclaimed believed is burn
 * (blast ate it).
 */
internal fun flowsFor(
    holder: HolderId,
    believed: Map<ItemKey, Long>,
    result: BlockDropCorrelator.ReleaseResult,
): List<Flow> {
    val available = believed.toMutableMap()
    val mints = mutableListOf<Flow>()
    val moves = mutableListOf<Flow>()

    for ((itemKey, quantity, entity) in result.claimed) {
        val have = available[itemKey] ?: 0L
        if (have < quantity) {
            mints += Flow(itemKey, Quantity(quantity - have), WORLDGEN_SOURCE, holder, FlowKind.MINT)
            available[itemKey] = quantity
        }
        available[itemKey] = available.getValue(itemKey) - quantity
        moves += Flow(itemKey, Quantity(quantity), holder, entity, FlowKind.MOVE)
    }

    val burns = available.filterValues { it > 0L }
        .map { (itemKey, left) -> Flow(itemKey, Quantity(left), holder, DESTROYED_SINK, FlowKind.BURN) }

    return mints + moves + burns
}

/**
 * Self-spawned drops: [release] is physical truth; [believed] is ledger history only.
 */
internal fun releaseFlows(
    holder: HolderId,
    believed: Map<ItemKey, Long>,
    release: Map<ItemKey, Long>,
    spawned: List<InventoryDelta>,
): List<Flow> {
    val mints = release.mapNotNull { (itemKey, qty) ->
        val have = believed[itemKey] ?: 0L
        if (qty <= have) null else Flow(itemKey, Quantity(qty - have), WORLDGEN_SOURCE, holder, FlowKind.MINT)
    }
    val moves = spawned.map { Flow(it.itemKey, Quantity(it.delta), holder, it.holder, FlowKind.MOVE) }
    val burns = believed.mapNotNull { (itemKey, have) ->
        val qty = release[itemKey] ?: 0L
        if (have <= qty) null else Flow(itemKey, Quantity(have - qty), holder, DESTROYED_SINK, FlowKind.BURN)
    }
    return mints + moves + burns
}

/**
 * Mint onto the plant then move: a direct mint into the hand has no block on the spatial index,
 * so a region rollback resets the bush and leaves the berries.
 */
internal fun harvestFlows(
    totals: Map<ItemKey, Long>,
    from: HolderId,
    to: HolderId,
): List<Flow> = totals.flatMap { (itemKey, amount) ->
    listOf(
        Flow(itemKey, Quantity(amount), WORLDGEN_SOURCE, from, FlowKind.MINT),
        Flow(itemKey, Quantity(amount), from, to, FlowKind.MOVE),
    )
}

internal fun worldgenMintFlows(
    totals: Map<ItemKey, Long>,
    into: HolderId,
): List<Flow> = totals.map { (itemKey, amount) ->
    Flow(itemKey, Quantity(amount), WORLDGEN_SOURCE, into, FlowKind.MINT)
}

/**
 * Only players: their inventory is live and never credited later. Minting a ground item whose
 * block->ground claim is still in flight duplicates the lot (pickup already emptied the entity).
 * Pre-plugin chests mint on the slow path, which can tell unseen from not-yet-credited.
 */
internal fun ignoranceIsPermanent(holder: HolderId): Boolean = holder is HolderId.Player
