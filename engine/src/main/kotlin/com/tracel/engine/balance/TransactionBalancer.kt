package com.tracel.engine.balance

import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.holder.stableSortKey
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/**
 * Turns raw "this holder gained / lost N units" observations into a balanced
 * list of [Flow]s, without needing to know why the change happened.
 */
public class TransactionBalancer {
    public fun balance(deltas: List<InventoryDelta>): List<Flow> =
        deltas.filter { it.delta != 0L }
            .groupBy { it.itemKey }
            .flatMap { (itemKey, sameItem) -> balanceOneItem(itemKey, sameItem) }

    private fun balanceOneItem(itemKey: ItemKey, deltas: List<InventoryDelta>): List<Flow> {
        val gains = deltas.filter { it.delta > 0 }.sortedBy { it.holder.stableSortKey() }
            .mapTo(ArrayDeque()) { Unpaired(it.holder, it.delta, it.fromGap) }
        val losses = deltas.filter { it.delta < 0 }.sortedBy { it.holder.stableSortKey() }
            .mapTo(ArrayDeque()) { Unpaired(it.holder, -it.delta, it.fromGap) }
        val flows = mutableListOf<Flow>()

        while (gains.isNotEmpty() && losses.isNotEmpty()) {
            val gain = gains.removeFirst()
            val loss = losses.removeFirst()
            val matched = minOf(gain.amount, loss.amount)
            flows += Flow(itemKey, Quantity(matched), loss.holder, gain.holder, FlowKind.MOVE)
            if (gain.amount > matched) gains.addFirst(gain.copy(amount = gain.amount - matched))
            if (loss.amount > matched) losses.addFirst(loss.copy(amount = loss.amount - matched))
        }

        // What could not be paired is a signal: items appeared or disappeared without a visible source / receiver in
        // this set of deltas. Each such case becomes an explicit typed "MINT" / "BURN", rather than silently lost.
        for ((holder, amt, fromGap) in gains) {
            val source = SourceKind.UNTRACKED_GAP.takeIf { fromGap } ?: SourceKind.UNATTRIBUTED
            flows += Flow(itemKey, Quantity(amt), HolderId.Source(source), holder, FlowKind.MINT)
        }
        for ((holder, amt, fromGap) in losses) {
            val sink = SinkKind.UNTRACKED_GAP.takeIf { fromGap } ?: SinkKind.UNATTRIBUTED
            flows += Flow(itemKey, Quantity(amt), holder, HolderId.Sink(sink), FlowKind.BURN)
        }
        return flows
    }

    internal data class Unpaired(val holder: HolderId, val amount: Long, val fromGap: Boolean)
}
