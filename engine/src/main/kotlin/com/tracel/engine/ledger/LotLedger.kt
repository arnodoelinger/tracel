package com.tracel.engine.ledger

import com.tracel.annotations.Consume
import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ledger.craft.CraftResult
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId
import com.tracel.platform.storage.UnitOfWork

/**
 * The provenance ledger's core: which lots sit where, and what happens when an
 * account is withdrawn from.
 *
 * Items have no identity — ten coins are not ten objects, just a
 * quantity. So "which specific coins did this player just take" isn't a
 * fact anyone can observe, it's a policy that has to be chosen...
 *
 * This ledger chooses [FIFO](https://en.wikipedia.org/wiki/FIFO_and_LIFO_accounting):
 * whichever batch has sat in an account longest is the first one spent.
 * That's the same rule a warehouse uses for stock with no serial
 * numbers — not because it's the only possible answer, but because it's a
 * consistent, deterministic one, which is what makes "where did this item
 * come from" a question with a reproducible answer at all. Thank God I'm in
 * an economic university, otherwise I would look at this and
 * be amazed at what the hell is going on here.
 *
 * A lot's identity survives it moving between holders: relocating an entire
 * placement keeps the same lot id. Only a partial withdrawal — taking less
 * than an account's oldest batch holds — creates two new lots (the part taken,
 * the part left behind), each linked back to the original by a
 * [LotEdge.Split]. That is the one and only place new lots get created out of
 * an existing one; everything else in this class is bookkeeping around it.
 *
 * Every operation here suspends and runs inside one [UnitOfWork.atomically] —
 * a withdrawal is eight-odd repository calls, and it has no business being
 * eight commits or eight trips to the storage thread.
 *
 * @see <a href="https://en.wikipedia.org/wiki/FIFO_and_LIFO_accounting">FIFO and LIFO accounting</a>
 */
@RunsOn(ThreadContext.STORAGE)
public class LotLedger(private val repo: LotRepository) : UnitOfWork by repo {
    /**
     * Brings [quantity] of [itemKey] into the world with no history, placed at [holder]: a drop, world generation, a
     * rollback mint.
     *
     * The lot records [txn] as the transaction that created it and has no edge to any other lot.
     */
    public suspend fun mint(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): Lot = atomically {
        val lot = repo.createLot(itemKey, quantity, txn)
        repo.place(holder, lot.id, quantity)
        lot
    }

    /**
     * Takes [quantity] of [itemKey] out of [holder], oldest lots first, splitting the last one if it holds more than is
     * still needed.
     *
     * Returns the portions taken, which are placed nowhere until the caller [deposit]s them.
     *
     * The balance is checked before anything is touched and a shortfall throws, so a failed withdrawal leaves the
     * account as it was. It used to take whatever it could reach and throw afterwards.
     */
    @Consume
    public suspend fun withdraw(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): List<LotPortion> =
        atomically {
            val available = repo.totalOf(holder, itemKey)
            check(available >= quantity.raw) {
                "insufficient balance at $holder for $itemKey: needed ${quantity.raw}, have $available"
            }

            repo.takeFifo(holder, itemKey, quantity, txn)
        }

    /**
     * [withdraw] for several destinations at once: [owed] says how much each gets, in order, and the oldest lots are
     * dealt out along it in one pass.
     *
     * A destination that received nothing is left out of the result.
     *
     * Throws, touching nothing, if [holder] holds less than the sum of [owed].
     */
    @Consume
    public suspend fun drain(
        holder: HolderId,
        itemKey: ItemKey,
        owed: List<Pair<HolderId, Long>>,
        txn: TxnId,
    ): List<Pair<HolderId, List<LotPortion>>> = atomically {
        val wanted = owed.sumOf { it.second }
        val available = repo.totalOf(holder, itemKey)
        check(available >= wanted) {
            "insufficient balance at $holder for $itemKey: owed $wanted, have $available"
        }
        repo.drainFifo(holder, itemKey, owed, txn)
    }

    /**
     * Takes the whole placement of [lotId] out of [holder], whichever place it has in the queue.
     *
     * A rollback has to take the lot it traced, not whichever sibling happens to be oldest.
     *
     * Throws if the lot is not placed at [holder].
     */
    public suspend fun withdrawExact(holder: HolderId, lotId: LotId): LotPortion = atomically {
        val remaining = repo.placementOf(holder, lotId)?.remaining
            ?: error("lot $lotId is not currently placed at $holder")
        repo.remove(holder, lotId)
        LotPortion(lotId, remaining)
    }

    /**
     * Moves [lotId] from [from] to [to] with its whole placement, keeping its place in the queue order. Returns the
     * quantity it held.
     *
     * Throws if the lot is not placed at [from].
     */
    public suspend fun moveExact(from: HolderId, to: HolderId, lotId: LotId): Quantity = atomically {
        repo.rehome(from, to, lotId)
    }

    /**
     * [moveExact] for many lots in one go; a whole pack of them can move as a single operation.
     *
     * @return what each held
     */
    public suspend fun moveExactAll(from: HolderId, to: HolderId, lotIds: List<LotId>): Map<LotId, Quantity> =
        atomically { repo.rehomeAll(from, to, lotIds) }

    /**
     * Places [portions], already taken out of somewhere, at [holder], each as the newest entry of its account.
     *
     * Throws if one of the lots is still placed.
     */
    public suspend fun deposit(holder: HolderId, portions: List<LotPortion>): Unit = atomically {
        repo.placeAll(holder, portions)
    }

    /**
     * [withdraw] from [holder] and [deposit] on the [HolderId.Sink] for [reason].
     *
     * Sinks are holders like any other here; [census] is what leaves them out of the total.
     */
    public suspend fun burn(
        holder: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        reason: SinkKind,
        txn: TxnId
    ): List<LotPortion> =
        atomically {
            val portions = withdraw(holder, itemKey, quantity, txn)
            deposit(HolderId.Sink(reason), portions)
            portions
        }

    /**
     * [withdraw] from [from] and [deposit] on [to].
     *
     * @return the portions that moved.
     */
    public suspend fun move(
        from: HolderId,
        to: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId
    ): List<LotPortion> =
        atomically {
            val portions = withdraw(from, itemKey, quantity, txn)
            deposit(to, portions)
            portions
        }

    /**
     * Sends [quantity] of [itemKey] that came from [lotId] back to [to], the way a rollback gives material back.
     *
     * What happens depends on what [from] still holds of that lot:
     * - Exactly [quantity]: the placement moves whole
     * - More: [quantity] is split off the lot and moved
     * - Less, or nothing because it was split or moved on: the pieces the lot split into are followed, and only what
     *   they cannot cover is taken by FIFO
     */
    public suspend fun moveBack(
        from: HolderId,
        to: HolderId,
        lotId: LotId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): Unit = atomically {
        val traced = repo.placementOf(from, lotId)?.remaining?.raw ?: 0L
        when {
            traced > quantity.raw -> deposit(to, listOf(takeExactly(from, lotId, quantity, txn)))
            traced == quantity.raw -> repo.rehome(from, to, lotId)

            // Moved or split since: follow its pieces, as a withdrawal does, before any FIFO fallback
            else -> deposit(to, withdrawBack(from, lotId, itemKey, quantity, txn))
        }
    }

    /**
     * Burns [quantity] of [itemKey] that came from [lotId] where it sits at [from], following split pieces and falling
     * back to FIFO for what they cannot cover.
     *
     * The burn-side twin of [moveBack].
     */
    public suspend fun burnBack(
        from: HolderId,
        lotId: LotId,
        itemKey: ItemKey,
        quantity: Quantity,
        reason: SinkKind,
        txn: TxnId,
    ): Unit = atomically {
        deposit(HolderId.Sink(reason), withdrawBack(from, lotId, itemKey, quantity, txn))
    }

    /**
     * Takes [quantity] out of [from], preferring [lotId] and then the lots it split into, and FIFO only for what those
     * cannot cover, and never for a [HolderId.Source] or [HolderId.Sink], which have nothing to fall back on. Returns
     * the portions.
     *
     * Walks the split edges breadth first. A lot placed at [from] is taken whole when it fits in what is still owed, or
     * split when it does not.
     */
    private suspend fun withdrawBack(
        from: HolderId,
        lotId: LotId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): List<LotPortion> {
        var owed = quantity.raw
        val taken = ArrayList<LotPortion>()
        val frontier = ArrayDeque(listOf(lotId))
        val seen = HashSet<LotId>()
        while (owed > 0L && frontier.isNotEmpty()) {
            val id = frontier.removeFirst()
            if (!seen.add(id)) continue
            val placed = repo.placementOf(from, id)?.remaining
            if (placed == null) {
                for (edge in repo.edgesFrom(id)) if (edge is LotEdge.Split) frontier += edge.child
            } else if (placed.raw <= owed) {
                taken += withdrawExact(from, id)
                owed -= placed.raw
            } else {
                taken += takeExactly(from, id, Quantity(owed), txn)
                owed = 0L
            }
        }
        if (owed > 0L && !from.isPseudo()) taken += withdraw(from, itemKey, Quantity(owed), txn)
        return taken
    }

    /**
     * Splits [quantity] off [lotId], placed at [holder], into a new lot of its own, and leaves the rest as another new
     * lot in the same queue slot. Both are linked to the original by split edges.
     *
     * When [quantity] is the whole placement or more, takes it whole instead.
     */
    private suspend fun takeExactly(holder: HolderId, lotId: LotId, quantity: Quantity, txn: TxnId): LotPortion {
        val placed = repo.placementOf(holder, lotId) ?: error("lot $lotId is not currently placed at $holder")
        val left = (placed.remaining - quantity) ?: return withdrawExact(holder, lotId)
        val taken = repo.createLot(placed.lot.itemKey, quantity, txn)
        val kept = repo.createLot(placed.lot.itemKey, left, txn)
        repo.recordEdge(LotEdge.Split(taken.id, lotId, quantity))
        repo.recordEdge(LotEdge.Split(kept.id, lotId, left))
        repo.replace(holder, lotId, kept.id, left)
        return LotPortion(taken.id, quantity)
    }

    /** Whether this holder only stands for the world outside the ledger. */
    private fun HolderId.isPseudo(): Boolean = this is HolderId.Source || this is HolderId.Sink

    /**
     * Places a lot that was withdrawn earlier at [holder] again. It keeps its id and gains no history.
     *
     * Throws if the lot is placed anywhere.
     */
    public suspend fun restore(holder: HolderId, lotId: LotId, quantity: Quantity): Unit = atomically {
        repo.currentHolderOf(lotId)?.let { error("lot $lotId is already placed at $it") }
        repo.place(holder, lotId, quantity)
    }

    /**
     * Consumes [ingredients] oldest first, creates the output lot for [product] at its holder, and links every consumed
     * portion to it with a [LotEdge.Transform].
     *
     * @return the output and the portions consumed, grouped by ingredient
     */
    public suspend fun craft(ingredients: List<Ingredient>, product: Product, txn: TxnId): CraftResult = atomically {
        val consumed = ingredients.map { withdraw(it.holder, it.itemKey, it.quantity, txn) }
        val outputLot = repo.createLot(product.itemKey, product.quantity, txn)
        repo.place(product.holder, outputLot.id, product.quantity)
        for ((lotId, quantity) in consumed.flatten()) {
            repo.recordEdge(LotEdge.Transform(outputLot.id, lotId, quantity, txn, product.holder))
        }
        CraftResult(outputLot, consumed)
    }

    /**
     * Does a craft again that a rollback unmade: the exact inverse of the [withdrawExact] and [restore] pair an unmake
     * does.
     *
     * [inputs] are consumed at [holder], following split pieces. Pieces other than the original lot, such as a split
     * part or a stand-in taken by FIFO, are linked to the first of [outputs] by a [LotEdge.Transform]; without that
     * edge a later rollback of the piece's own history found nothing to put back. Then every output is [restore]d where
     * it was.
     */
    public suspend fun recraft(
        holder: HolderId,
        outputs: List<Pair<HolderId, LotPortion>>,
        inputs: List<Pair<LotId, Quantity>>,
        txn: TxnId,
    ): Unit = atomically {
        val output = outputs.firstOrNull()?.second?.lotId
        for ((lotId, quantity) in inputs) {
            val taken = withdrawBack(holder, lotId, repo.lot(lotId).itemKey, quantity, txn)
            if (output == null) continue

            // A split piece or a FIFO stand-in went into the remade output too: without the edge it just
            // vanished, and a later rollback of its own history found it gone and put nothing back.
            for ((piece, amount) in taken) {
                if (piece != lotId) repo.recordEdge(LotEdge.Transform(output, piece, amount, txn, holder))
            }
        }
        for ((at, portion) in outputs) restore(at, portion.lotId, portion.quantity)
    }

    /**
     * Creates a stand-in of [quantity] at [holder] for [originalLotId], which is gone, and links the two with a
     * [LotEdge.Compensate] for [job].
     */
    public suspend fun compensate(
        holder: HolderId,
        originalLotId: LotId,
        quantity: Quantity,
        txn: TxnId,
        job: RollbackJobId
    ): Lot =
        atomically {
            val itemKey = repo.lot(originalLotId).itemKey
            val lot = repo.createLot(itemKey, quantity, txn)
            repo.place(holder, lot.id, quantity)
            repo.recordEdge(LotEdge.Compensate(lot.id, originalLotId, quantity, job))
            lot
        }

    /** The lot [job] created in place of [originalLotId], or `null` if it created none. */
    public suspend fun compensationOf(originalLotId: LotId, job: RollbackJobId): LotId? =
        repo.findCompensateEdge(originalLotId, job)?.child

    /**
     * Forgets the [LotEdge.Compensate] that [job] recorded for [originalLotId], if there is one. The stand-in lot
     * stays.
     */
    public suspend fun uncompensate(originalLotId: LotId, job: RollbackJobId): Unit = atomically {
        val edge = repo.findCompensateEdge(originalLotId, job) ?: return@atomically
        repo.removeEdge(edge.parent, edge.child)
    }

    /**
     * Warms whatever [itemKeyOf] and [quantityOf] read for all of [ids] in one pass, for a store that reads lots
     * lazily.
     */
    public suspend fun prefetchLots(ids: Collection<LotId>): Unit = repo.prefetchLots(ids)

    /** What [lotId] is a lot of. */
    public suspend fun itemKeyOf(lotId: LotId): ItemKey = repo.lot(lotId).itemKey

    /** How much [lotId] was created with, not how much of it is left in its placement. */
    public suspend fun quantityOf(lotId: LotId): Quantity = repo.lot(lotId).quantity

    /** Where [lotId] is placed right now, or `null` if nowhere. */
    public suspend fun currentHolderOf(lotId: LotId): HolderId? = repo.currentHolderOf(lotId)

    /** What [holder] holds of [itemKey], or `null` if nothing. */
    public suspend fun totalAt(holder: HolderId, itemKey: ItemKey): Quantity? =
        repo.totalOf(holder, itemKey).takeIf { it > 0 }?.let(::Quantity)

    /** Everything [holder] holds, per item key. Items it holds none of are left out. */
    public suspend fun totalsAt(holder: HolderId): Map<ItemKey, Quantity> {
        val raw = repo.totalsAt(holder)
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, Quantity>(raw.size)
        for ((key, total) in raw) {
            if (total > 0L) out[key] = Quantity(total)
        }
        return if (out.isEmpty()) emptyMap() else out
    }

    /**
     * Units of [itemKey] on real holders and in escrow, not on a [HolderId.Source] or [HolderId.Sink]: how many exist.
     */
    public suspend fun census(itemKey: ItemKey): Long = repo.census(itemKey)

    /**
     * Drops the placement of [lotId] at [holder], quietly if there is none. Not a FIFO take, and nothing is deposited.
     */
    public suspend fun destroy(holder: HolderId, lotId: LotId) {
        repo.remove(holder, lotId)
    }
}
