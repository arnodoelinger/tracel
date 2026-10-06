package com.tracel.plugin.rollback.survey.rooting

import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.Transaction
import java.util.*
import kotlin.math.abs

private const val PAIR_BEFORE_MS = 2_000L

private const val PAIR_AFTER_MS = 15_000L

/**
 * Gap mints that are the other half of a burn: the differ saw one move as two, a burn from the giver and, a moment
 * later, a mint into the taker. Rolling back the burn hands the item to its old holder; the mint has to go with it,
 * or the item is in two places. Whole lots only, and no more than the burn covers.
 *
 * @return the mint lots, each rooted at [SourceKind.UNTRACKED_GAP] so the rollback takes them out of the world.
 */
internal fun pairedGapMints(txns: List<Transaction>, lotsOf: (Transaction) -> List<FlowLot>): Map<LotId, HolderId> {
    class Half(val key: ItemKey, val at: Long, val lot: LotId, val size: Long)

    val burns = ArrayList<Half>()
    val mints = ArrayList<Half>()
    for (txn in txns) {
        for ((flowIndex, lotId, quantity) in lotsOf(txn)) {
            val flow = txn.flows.getOrNull(flowIndex) ?: continue
            val half = Half(flow.itemKey, txn.epochMillis, lotId, quantity.raw)
            when {
                (flow.destination as? HolderId.Sink)?.kind == SinkKind.UNATTRIBUTED && flow.source !is HolderId.Source ->
                    burns += half

                (flow.source as? HolderId.Source)?.kind == SourceKind.UNTRACKED_GAP && flow.destination !is HolderId.Sink ->
                    mints += half
            }
        }
    }
    if (burns.isEmpty() || mints.isEmpty()) return emptyMap()

    val out = HashMap<LotId, HolderId>()
    for (burn in burns.sortedBy { it.at }) {
        var need = burn.size
        val near = mints.filter {
            it.key == burn.key && it.lot !in out && it.at - burn.at in -PAIR_BEFORE_MS..PAIR_AFTER_MS
        }
        for (mint in near.sortedBy { abs(it.at - burn.at) }) {
            if (need <= 0L) break
            if (mint.size > need) continue
            need -= mint.size
            out[mint.lot] = HolderId.Source(SourceKind.UNTRACKED_GAP)
        }
    }
    return out
}
