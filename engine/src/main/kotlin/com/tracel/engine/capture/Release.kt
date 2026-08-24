package com.tracel.engine.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.ledger.LotLedger
import com.tracel.model.holder.HolderId

/**
 * Every delta needed to release everything [holder] is currently believed to have, as losses —
 * shared by every listener that has to say "this holder stopped existing" (a broken container,
 * a broken placed block, an exploded block) without knowing exactly where the contents went.
 */
public fun LotLedger.releaseDeltas(holder: HolderId): List<InventoryDelta> =
    totalsAt(holder).map { (itemKey, qty) -> InventoryDelta(holder, itemKey, -qty.raw) }
