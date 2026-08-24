package com.tracel.engine.ledger

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.UnitOfWork

/**
 * Storage port for lots, their edges, and where they currently sit.
 *
 * A lot's identity and its placement (which holder, how much remains, its
 * queue position) are tracked separately on purpose — see
 * [LotLedger][com.tracel.engine.ledger.LotLedger] for why a lot can move
 * between holders without ever becoming a "new" lot.
 */
public interface LotRepository : UnitOfWork {
    /** Creates a new lot of [itemKey] with [quantity], and records that it was created by [createdBy]. */
    public suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot

    /** Fetches the lot with [id], or throws if it does not exist. */
    public suspend fun lot(id: LotId): Lot

    /** Records that [edge] happened, linking a parent lot to a child lot. */
    public suspend fun recordEdge(edge: LotEdge)

    /** Edges where [lotId] is the parent - how its life continued after creation. */
    public suspend fun edgesFrom(lotId: LotId): List<LotEdge>

    /** Edges where [lotId] is the child - how it came to exist. */
    public suspend fun edgesInto(lotId: LotId): List<LotEdge>

    /**
     * [holder]'s queue for [itemKey], oldest first, at most [limit] entries.
     *
     * [limit] exists because a withdrawal almost never needs the whole queue: it takes from the
     * oldest lots until it has enough, and an account that has accumulated a few thousand
     * placements should not cost a few thousand rows to take one diamond out of.
     *
     * Callers that genuinely want the whole queue — a provenance walk. Say, simply leave it alone.
     */
    public suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int = Int.MAX_VALUE): List<AccountLot>

    /**
     * What [holder] holds of [itemKey] in total, without reading the queue that says so.
     *
     * The difference matters: summing a queue in `Kotlin` means materializing every placement in
     * it, and the accounts that get asked about most often — a busy player, a hopper's chest — are
     * exactly the ones with the longest queues.
     */
    public suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long

    /** Everything [holder] holds, summed per item key. The whole-account form of [totalOf]. */
    public suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long>

    /** [lotId]'s placement at [holder], or `null` if it is not placed there — a direct lookup, not a scan. */
    public suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot?

    /**
     * Every current placement of [itemKey], across every holder — including
     * the `Source` / `Sink` / `Escrow` pseudo-holders, since they are ordinary
     * holders to this repository.
     *
     * This is the raw material for a census: sum it up and you get "how many
     * units of this item exist anywhere right now," independent of and a check
     * against whatever the ledger's own bookkeeping claims.
     */
    public suspend fun allPlacements(itemKey: ItemKey): List<AccountLot>

    /** Every current placement at [holder], across every item key it holds. */
    public suspend fun placementsAt(holder: HolderId): List<AccountLot>

    /** Where [lotId] currently sits, or `null` if nothing places it anywhere right now. */
    public suspend fun currentHolderOf(lotId: LotId): HolderId?

    /** Appends a fresh queue entry for [lotId] at [holder], newest position. */
    public suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot

    /** Retires [lotId]'s placement at [holder] entirely. */
    public suspend fun remove(holder: HolderId, lotId: LotId)

    /**
     * Swaps which lot occupies an existing queue slot, keeping its FIFO
     * position. Used only when a placement is split: the portion that stays
     * behind is logically the same queue entry, just now a smaller lot.
     */
    public suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity)
}
