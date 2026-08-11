package com.tracel.engine.ledger

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge

/**
 * Storage port for lots, their edges, and where they currently sit.
 *
 * A lot's identity and its placement (which holder, how much remains, its
 * queue position) are tracked separately on purpose — see
 * [LotLedger][com.tracel.engine.ledger.LotLedger] for why a lot can move
 * between holders without ever becoming a "new" lot.
 */
public interface LotRepository {
    /** Creates a new lot of [itemKey] with [quantity], and records that it was created by [createdBy]. */
    public fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot

    /** Fetches the lot with [id], or throws if it does not exist. */
    public fun lot(id: LotId): Lot

    /** Records that [edge] happened, linking a parent lot to a child lot. */
    public fun recordEdge(edge: LotEdge)

    /** Edges where [lotId] is the parent - how its life continued after creation. */
    public fun edgesFrom(lotId: LotId): List<LotEdge>

    /** Edges where [lotId] is the child - how it came to exist. */
    public fun edgesInto(lotId: LotId): List<LotEdge>

    /** [holder]'s queue for [itemKey], oldest first. */
    public fun accountQueue(holder: HolderId, itemKey: ItemKey): List<AccountLot>

    /**
     * Every current placement of [itemKey], across every holder — including
     * the `Source` / `Sink` / `Escrow` pseudo-holders, since they are ordinary
     * holders to this repository.
     *
     * This is the raw material for a census: sum it up and you get "how many
     * units of this item exist anywhere right now," independent of and a check
     * against whatever the ledger's own bookkeeping claims.
     */
    public fun allPlacements(itemKey: ItemKey): List<AccountLot>

    /** Where [lotId] currently sits, or `null` if nothing places it anywhere right now. */
    public fun currentHolderOf(lotId: LotId): HolderId?

    /** Appends a fresh queue entry for [lotId] at [holder], newest position. */
    public fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot

    /** Retires [lotId]'s placement at [holder] entirely. */
    public fun remove(holder: HolderId, lotId: LotId)

    /**
     * Swaps which lot occupies an existing queue slot, keeping its FIFO
     * position. Used only when a placement is split: the portion that stays
     * behind is logically the same queue entry, just now a smaller lot.
     */
    public fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity)
}
