package com.tracel.engine.ledger.repository

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.Lot

/** The lots themselves: creating one and reading it back. */
public interface Lots {
    /** Creates a new lot of [itemKey] with [quantity], and records that it was created by [createdBy]. */
    public suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot

    /** Fetches the lot with [id], or throws if it does not exist. */
    public suspend fun lot(id: LotId): Lot

    /** Warms whatever [lot] reads from for every one of [ids], in one pass. */
    public suspend fun prefetchLots(ids: Collection<LotId>) {
        for (id in ids) runCatching { lot(id) }
    }

    /** [lot] for many ids in one snapshot. */
    public suspend fun lotsOfAll(ids: Collection<LotId>): Map<LotId, Lot> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, Lot>(ids.size)
        for (id in ids) out[id] = lot(id)
        return out
    }
}
