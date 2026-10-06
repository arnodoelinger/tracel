package com.tracel.plugin.rollback.survey.rooting

import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction

/**
 * Catches an item that was minted into a mob and immediately moved out of it, in the same
 * transaction.
 */
internal fun Transaction.mintedStraightThrough(): Map<HolderId, HolderId> {
    var mintedInto: MutableMap<Pair<HolderId, ItemKey>, HolderId>? = null
    for ((itemKey, _, source, into, kind) in flows) {
        if (kind != FlowKind.MINT) continue
        if (into !is HolderId.Entity) continue
        if (source !is HolderId.Source) continue
        (mintedInto ?: HashMap<Pair<HolderId, ItemKey>, HolderId>().also { mintedInto = it })[into to itemKey] =
            source
    }
    val minted = mintedInto ?: return emptyMap()
    val out = HashMap<HolderId, HolderId>(minted.size)
    for (flow in flows) {
        if (flow.kind != FlowKind.MOVE) continue
        val from = minted[flow.source to flow.itemKey] ?: continue
        out[flow.source] = from
    }
    return out
}
