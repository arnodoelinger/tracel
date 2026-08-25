package com.tracel.engine.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.ledger.LotLedger
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId

/**
 * Every delta needed to release everything [holder] is currently believed to have, as losses —
 * shared by every listener that has to say "this holder stopped existing" (a broken container,
 * a broken placed block, an exploded block) without knowing exactly where the contents went.
 */
public suspend fun LotLedger.releaseDeltas(holder: HolderId): List<InventoryDelta> =
    totalsAt(holder).map { (itemKey, qty) -> InventoryDelta(holder, itemKey, -qty.raw) }

/**
 * The flows for "everything [from] had is now at [to]" — the resolved form of a capture that
 * only ever named the two holders.
 *
 * A region thread cannot know what a despawning item entity or a breaking block held without
 * reading the ledger, and reading the ledger is not something a region thread does. So it
 * names the pair and this works out the quantities on the storage thread instead.
 */
public suspend fun LotLedger.releaseFlows(from: HolderId, to: HolderId): List<Flow> =
    totalsAt(from).map { (itemKey, quantity) ->
        Flow(itemKey, quantity, from, to, if (to is HolderId.Sink) FlowKind.BURN else FlowKind.MOVE)
    }
