package com.tracel.plugin.listener.support.flow

import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.plugin.listener.support.drop.BlockDrop

val WORLDGEN_SOURCE: HolderId.Source = HolderId.Source(SourceKind.WORLDGEN)
val DESTROYED_SINK: HolderId.Sink = HolderId.Sink(SinkKind.UNATTRIBUTED)

/**
 * Generates a list of transaction flows (mints, moves, and burns) based on the provided believed state,
 * claimed items, and their respective resulting destinations or sources.
 */
internal fun flowsFor(
    holder: HolderId,
    believed: Map<ItemKey, Long>,
    result: BlockDrop.ReleaseResult,
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
 * Releases items from a holder, generating flows for mints, moves, and burns based on the believed
 * state and the actual release.
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

/** Generates flows for minting items during world generation, based on the provided totals and destination holder. */
internal fun worldgenMintFlows(
    totals: Map<ItemKey, Long>,
    into: HolderId,
): List<Flow> = totals.map { (itemKey, amount) ->
    Flow(itemKey, Quantity(amount), WORLDGEN_SOURCE, into, FlowKind.MINT)
}

/** @return `true` if the holder is a player, indicating that ignorance of its contents is permanent. */
internal fun ignoranceIsPermanent(holder: HolderId): Boolean = holder is HolderId.Player

/** A holder's contents destroyed in place, with nothing dropped. */
internal fun destroyedFlows(
    holder: HolderId,
    believed: Map<ItemKey, Long>,
    present: Map<ItemKey, Long>,
): List<Flow> {
    val mints = present.mapNotNull { (itemKey, qty) ->
        val have = believed[itemKey] ?: 0L
        if (qty <= have) null else Flow(itemKey, Quantity(qty - have), WORLDGEN_SOURCE, holder, FlowKind.MINT)
    }
    val burns = (believed.keys + present.keys).mapNotNull { itemKey ->
        val gone = maxOf(believed[itemKey] ?: 0L, present[itemKey] ?: 0L)
        if (gone <= 0L) null else Flow(itemKey, Quantity(gone), holder, DESTROYED_SINK, FlowKind.BURN)
    }
    return mints + burns
}
