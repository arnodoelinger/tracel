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
 *
 * Capture code does not have to correctly interpret every `Bukkit` inventory
 * event to get this right — shift-clicks, drags, hopper transfers all produce
 * the same kind of evidence: some holders end up with more of an item, others
 * with less.
 *
 * This class pairs the losses against the gains, and whatever cannot be matched
 * becomes an explicit, typed mint or burn instead of silently vanishing.
 *
 * Being wrong about why something moved is recoverable; being wrong about how much
 * moved is not — so this is built to never get the second one wrong, even with
 * no idea about the first.
 */
public class TransactionBalancer {
    public fun balance(deltas: List<InventoryDelta>): List<Flow> =
        deltas.filter { it.delta != 0L }
            .groupBy { it.itemKey }
            .flatMap { (itemKey, sameItem) -> balanceOneItem(itemKey, sameItem) }

    private fun balanceOneItem(itemKey: ItemKey, deltas: List<InventoryDelta>): List<Flow> {
        val gains = deltas.filter { it.delta > 0 }.sortedBy { it.holder.stableSortKey() }
            .mapTo(ArrayDeque()) { it.holder to it.delta }
        val losses = deltas.filter { it.delta < 0 }.sortedBy { it.holder.stableSortKey() }
            .mapTo(ArrayDeque()) { it.holder to -it.delta }
        val flows = mutableListOf<Flow>()

        while (gains.isNotEmpty() && losses.isNotEmpty()) {
            val (dst, gainAmt) = gains.removeFirst()
            val (src, lossAmt) = losses.removeFirst()
            val matched = minOf(gainAmt, lossAmt)
            flows += Flow(itemKey, Quantity(matched), src, dst, FlowKind.MOVE)
            if (gainAmt > matched) gains.addFirst(dst to (gainAmt - matched))
            if (lossAmt > matched) losses.addFirst(src to (lossAmt - matched))
        }

        // What could not be paired is a signal: items appeared or disappeared without a visible source / receiver in
        // this set of deltas. Each such case becomes an explicit typed "MINT" / "BURN", rather than silently lost.
        for ((holder, amt) in gains) {
            flows += Flow(itemKey, Quantity(amt), HolderId.Source(SourceKind.UNATTRIBUTED), holder, FlowKind.MINT)
        }
        for ((holder, amt) in losses) {
            flows += Flow(itemKey, Quantity(amt), holder, HolderId.Sink(SinkKind.UNATTRIBUTED), FlowKind.BURN)
        }
        return flows
    }
}
