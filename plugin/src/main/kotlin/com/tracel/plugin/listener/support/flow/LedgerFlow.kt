package com.tracel.plugin.listener.support.flow

import com.tracel.plugin.listener.support.drop.BlockDrop
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

val WORLDGEN_SOURCE: HolderId.Source = HolderId.Source(SourceKind.WORLDGEN)
val DESTROYED_SINK: HolderId.Sink = HolderId.Sink(SinkKind.UNATTRIBUTED)

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

internal fun ignoranceIsPermanent(holder: HolderId): Boolean = holder is HolderId.Player
