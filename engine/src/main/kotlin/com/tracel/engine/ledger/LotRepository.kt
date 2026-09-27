package com.tracel.engine.ledger

import com.tracel.annotations.Consume
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.UnitOfWork

/**
 * Storage port for lots, their edges, and where they currently sit.
 *
 * @see LotLedger
 */
public interface LotRepository : UnitOfWork {
    /** Creates a new lot of [itemKey] with [quantity], and records that it was created by [createdBy]. */
    public suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot

    /** Fetches the lot with [id], or throws if it does not exist. */
    public suspend fun lot(id: LotId): Lot

    /** Records that [edge] happened, linking a parent lot to a child lot. */
    public suspend fun recordEdge(edge: LotEdge)

    /** Forgets the edge from [parent] to [child]. */
    public suspend fun removeEdge(parent: LotId, child: LotId)

    /** Edges where [lotId] is the parent - how its life continued after creation. */
    public suspend fun edgesFrom(lotId: LotId): List<LotEdge>

    /**
     * The compensation [job] recorded for [originalLotId], if any — a point get, not a scan of
     * every outgoing edge.
     */
    public suspend fun findCompensateEdge(originalLotId: LotId, job: RollbackJobId): LotEdge.Compensate? {
        val edges = edgesFrom(originalLotId)
        var i = 0
        val n = edges.size
        while (i < n) {
            val edge = edges[i]
            if (edge is LotEdge.Compensate && edge.rollbackJob == job) return edge
            i++
        }
        return null
    }

    /**
     * [edgesFrom] for many lots in one snapshot. A rollback of a few thousand roots was opening
     * a prefix scan per lot in single file; the scans share nothing and run together.
     *
     * Every ID is present in the result, including lots with no outgoing edges.
     */
    public suspend fun edgesFromAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, List<LotEdge>>(ids.size)
        for (id in ids) out[id] = edgesFrom(id)
        return out
    }

    /** Edges where [lotId] is the child - how it came to exist. */
    public suspend fun edgesInto(lotId: LotId): List<LotEdge>

    /** [edgesInto] for many lots in one snapshot. Same reason as [edgesFromAll]. */
    public suspend fun edgesIntoAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, List<LotEdge>>(ids.size)
        for (id in ids) out[id] = edgesInto(id)
        return out
    }

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

    /** [currentHolderOf] for many lots in one snapshot. Missing ids are absent from the map. */
    public suspend fun currentHoldersOf(ids: Collection<LotId>): Map<LotId, HolderId> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, HolderId>(ids.size)
        for (id in ids) currentHolderOf(id)?.let { out[id] = it }
        return out
    }

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

    /** FIFO-consume [quantity] from the live queue, oldest first, splitting the last lot. */
    @Consume
    public suspend fun takeFifo(
        holder: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): List<LotPortion>

    /**
     * Same FIFO consume as [takeFifo], handed to several destinations in [owed] order, one pass.
     */
    @Consume
    public suspend fun drainFifo(
        holder: HolderId,
        itemKey: ItemKey,
        owed: List<Pair<HolderId, Long>>,
        txn: TxnId,
    ): List<Pair<HolderId, List<LotPortion>>>

    /** What [holder] holds of [itemKey] in total, without reading the queue that says so. */
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

    /** Units of [itemKey] sitting on real holders and escrow, not `Source` / `Sink`. */
    public suspend fun census(itemKey: ItemKey): Long {
        var total = 0L
        val placements = allPlacements(itemKey)
        var i = 0
        val n = placements.size
        while (i < n) {
            val at = placements[i]
            when (at.holder) {
                is HolderId.Source, is HolderId.Sink -> {}
                else -> total += at.remaining.raw
            }
            i++
        }
        return total
    }

    /** Every current placement at [holder], across every item key it holds. */
    public suspend fun placementsAt(holder: HolderId): List<AccountLot>

    /** Where [lotId] currently sits, or `null` if nothing places it anywhere right now. */
    public suspend fun currentHolderOf(lotId: LotId): HolderId?

    /** Appends a fresh queue entry for [lotId] at [holder], newest position. */
    public suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot

    /** [place] for each of [portions] at [holder], in order. */
    public suspend fun placeAll(holder: HolderId, portions: List<LotPortion>) {
        for ((lotId, quantity) in portions) place(holder, lotId, quantity)
    }

    /** Retires [lotId]'s placement at [holder] entirely. */
    public suspend fun remove(holder: HolderId, lotId: LotId)

    /** Moves one lot from [from] to [to] in place. Throws if it is not at [from]; returns what it held. */
    public suspend fun rehome(from: HolderId, to: HolderId, lotId: LotId): Quantity

    /** [rehome] for many lots at once. Throws if any is not at [from]; returns what each held. */
    public suspend fun rehomeAll(from: HolderId, to: HolderId, lotIds: List<LotId>): Map<LotId, Quantity> {
        val out = LinkedHashMap<LotId, Quantity>(lotIds.size)
        for (lotId in lotIds) out[lotId] = rehome(from, to, lotId)
        return out
    }

    /** Moves on every committed change. Zero where nobody counts. */
    public suspend fun version(): Long = 0L

    /** Whether any of [lots] changed after [witness], a [version] read earlier. Without stamps, whether anything did. */
    public suspend fun changedSince(lots: Collection<LotId>, witness: Long): Boolean = version() != witness

    /** Moves every placement at [from] over to [to], lots, quantities and queue positions intact. */
    public suspend fun relocate(from: HolderId, to: HolderId)

    /**
     * Swaps which lot occupies an existing queue slot, keeping its FIFO
     * position. Used only when a placement is split: the portion that stays
     * behind is logically the same queue entry, just now a smaller lot.
     */
    public suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity)
}
