package com.tracel.engine.ledger.repository

import com.tracel.engine.ledger.LotPortion
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.LotId

/** Where lots sit right now, and every way of putting them somewhere else. */
public interface Placements {
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

    /** [currentHolderOf] for many lots in one snapshot. Missing ids are absent from the map. */
    public suspend fun currentHoldersOf(ids: Collection<LotId>): Map<LotId, HolderId> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, HolderId>(ids.size)
        for (id in ids) currentHolderOf(id)?.let { out[id] = it }
        return out
    }

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

    /** Moves every placement at [from] over to [to], lots, quantities, and queue positions intact. */
    public suspend fun relocate(from: HolderId, to: HolderId)

    /**
     * Swaps which lot occupies an existing queue slot, keeping its FIFO
     * position. Used only when a placement is split: the portion that stays
     * behind is logically the same queue entry, just now a smaller lot.
     */
    public suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity)

    /**
     * Of [roots], those sitting placed and untouched since — no edge out of them — grouped by the
     * pack they share, so a planner can take each group whole instead of walking lot by lot.
     *
     * A store without packs finds none: everything is [PlacedRuns.rest], and walked.
     */
    public suspend fun placedRuns(roots: Collection<LotId>): PlacedRuns = PlacedRuns(emptyList(), roots.distinct())
}
