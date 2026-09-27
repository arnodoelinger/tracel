package com.tracel.engine.ledger

import com.tracel.annotations.Consume
import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.UnitOfWork

/**
 * The provenance ledger's core: which lots sit where, and what happens when an
 * account is withdrawn from.
 *
 * Minecraft items have no identity — ten diamonds are not ten objects, just a
 * quantity. So "which specific diamonds did this player just take" isn't a
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
    /** New lot with no history: drop, worldgen, rollback mint. */
    public suspend fun mint(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): Lot = atomically {
        val lot = repo.createLot(itemKey, quantity, txn)
        repo.place(holder, lot.id, quantity)
        lot
    }

    /**
     * FIFO withdraw. Splits the last lot if needed.
     *
     * Checks the balance first and throws without touching the account — a failed
     * withdraw used to eat whatever it could reach, then throw.
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

    /** Same FIFO as [withdraw], one pass for several destinations. */
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
     * Pulls this [lotId] out of [holder], ignoring FIFO.
     *
     * Rollback Take needs the traced lot, not whichever sibling is oldest in the queue.
     */
    public suspend fun withdrawExact(holder: HolderId, lotId: LotId): LotPortion = atomically {
        val remaining = repo.placementOf(holder, lotId)?.remaining
            ?: error("lot $lotId is not currently placed at $holder")
        repo.remove(holder, lotId)
        LotPortion(lotId, remaining)
    }

    /** Moves one lot to another holder, same FIFO slot. */
    public suspend fun moveExact(from: HolderId, to: HolderId, lotId: LotId): Quantity = atomically {
        val placed = repo.placementOf(from, lotId)?.remaining ?: error("lot $lotId is not currently placed at $from")
        if (from != to) repo.rehome(from, to, lotId)
        placed
    }

    /** Puts already-withdrawn portions onto [holder]. */
    public suspend fun deposit(holder: HolderId, portions: List<LotPortion>): Unit = atomically {
        for ((lotId, quantity) in portions) repo.place(holder, lotId, quantity)
    }

    /**
     * Withdraw and place on a [HolderId.Sink]. Sinks are real holders here;
     * [census] is what leaves them out of the world total.
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

    /** Withdraw from [from], deposit on [to]. */
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

    /** Sends [lotId] back to [to], falling back to FIFO for whatever of [quantity] it cannot cover. */
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
     * Burns [lotId] where it sits at [from], falling back to FIFO for whatever of [quantity] it
     * cannot cover — the burn-side twin of [moveBack].
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

    /** [lotId] and the pieces it split into first, FIFO only for what they cannot cover. */
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

    private fun HolderId.isPseudo(): Boolean = this is HolderId.Source || this is HolderId.Sink

    /** Puts a previously withdrawn lot back. Same lot id, no new history. */
    public suspend fun restore(holder: HolderId, lotId: LotId, quantity: Quantity): Unit = atomically {
        repo.currentHolderOf(lotId)?.let { error("lot $lotId is already placed at $it") }
        repo.place(holder, lotId, quantity)
    }

    /**
     * Consume [ingredients], mint [product], record a [LotEdge.Transform] per portion.
     * Returns consumed lots grouped by ingredient, plus the output.
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

    /** The exact inverse of the [withdrawExact] + [restore] pair an unmake does. */
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

    /** Mint a stand-in for a lot that is gone, linked with [LotEdge.Compensate]. */
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

    /** The lot [job] minted in place of [originalLotId], if it minted one. */
    public suspend fun compensationOf(originalLotId: LotId, job: RollbackJobId): LotId? =
        repo.findCompensateEdge(originalLotId, job)?.child

    /** Drop the Compensate edge for this job, if it exists. */
    public suspend fun uncompensate(originalLotId: LotId, job: RollbackJobId): Unit = atomically {
        val edge = repo.findCompensateEdge(originalLotId, job) ?: return@atomically
        repo.removeEdge(edge.parent, edge.child)
    }

    public suspend fun prefetchLots(ids: Collection<LotId>): Unit = repo.prefetchLots(ids)

    public suspend fun itemKeyOf(lotId: LotId): ItemKey = repo.lot(lotId).itemKey

    public suspend fun quantityOf(lotId: LotId): Quantity = repo.lot(lotId).quantity

    public suspend fun currentHolderOf(lotId: LotId): HolderId? = repo.currentHolderOf(lotId)

    /** Total of [itemKey] at [holder], or `null` if none. */
    public suspend fun totalAt(holder: HolderId, itemKey: ItemKey): Quantity? =
        repo.totalOf(holder, itemKey).takeIf { it > 0 }?.let(::Quantity)

    /** All item keys [holder] currently holds, zeros omitted. */
    public suspend fun totalsAt(holder: HolderId): Map<ItemKey, Quantity> {
        val raw = repo.totalsAt(holder)
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, Quantity>(raw.size)
        for ((key, total) in raw) {
            if (total > 0L) out[key] = Quantity(total)
        }
        return if (out.isEmpty()) emptyMap() else out
    }

    /** Units of [itemKey] on real holders and escrow. Not Source/Sink. */
    public suspend fun census(itemKey: ItemKey): Long = repo.census(itemKey)

    /** Drops this lot's placement, silently if there is none. Not a FIFO take. */
    public suspend fun destroy(holder: HolderId, lotId: LotId) {
        repo.remove(holder, lotId)
    }
}
