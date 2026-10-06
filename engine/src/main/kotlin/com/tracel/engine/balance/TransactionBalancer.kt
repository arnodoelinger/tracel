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
    /** Balances a list of [InventoryDelta]s into a list of [Flow]s. */
    public fun balance(deltas: List<InventoryDelta>): List<Flow> =
        deltas.filter { it.delta != 0L }
            .groupBy { it.itemKey }
            .flatMap { (itemKey, sameItem) -> balanceOneItem(itemKey, sameItem) }

    private fun balanceOneItem(itemKey: ItemKey, deltas: List<InventoryDelta>): List<Flow> {
        val gainList = deltas.filter { it.delta > 0 }.sortedBy { it.holder.stableSortKey() }
            .mapTo(ArrayList()) { Unpaired(it.holder, it.delta, it.fromGap) }
        val lossList = deltas.filter { it.delta < 0 }.sortedBy { it.holder.stableSortKey() }
            .mapTo(ArrayList()) { Unpaired(it.holder, -it.delta, it.fromGap) }
        val flows = mutableListOf<Flow>()

        val unmatched = gainList.iterator()
        while (unmatched.hasNext()) {
            val gain = unmatched.next()
            val loss = lossList.firstOrNull { it.amount == gain.amount } ?: continue
            lossList.remove(loss)
            unmatched.remove()
            flows += Flow(itemKey, Quantity(gain.amount), loss.holder, gain.holder, FlowKind.MOVE)
        }
        val gains = ArrayDeque(gainList)
        val losses = ArrayDeque(lossList)

        while (gains.isNotEmpty() && losses.isNotEmpty()) {
            val gain = gains.removeFirst()
            val loss = losses.removeFirst()
            val matched = minOf(gain.amount, loss.amount)
            flows += Flow(itemKey, Quantity(matched), loss.holder, gain.holder, FlowKind.MOVE)
            if (gain.amount > matched) gains.addFirst(gain.copy(amount = gain.amount - matched))
            if (loss.amount > matched) losses.addFirst(loss.copy(amount = loss.amount - matched))
        }
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
