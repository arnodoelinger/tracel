package com.tracel.engine.ledger

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
public class LotLedger(private val repo: LotRepository) : UnitOfWork by repo {
    /** A brand-new batch enters the ledger with no prior lot — e.g. a mob drop, worldgen, a rollback compensation. */
    public suspend fun mint(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): Lot = atomically {
        val lot = repo.createLot(itemKey, quantity, txn)
        repo.place(holder, lot.id, quantity)
        lot
    }

    /**
     * Takes [quantity] of [itemKey] out of [holder], oldest lots first,
     * splitting the last lot it has to touch. Throws if the account does not
     * hold enough — callers are expected to check first, since a shortfall
     * here means the caller's own bookkeeping is wrong, not that this is a
     * normal, recoverable outcome.
     *
     * The shortfall check happens before anything is mutated. Deliberately. It used to run
     * at the end, after the loop had already retired every lot it managed to reach: a withdrawal
     * of 10 from an account holding 4 removed those 4 from the account, then threw — and since
     * every caller catches that throw as the ordinary "untracked material" case and moves on,
     * the 4 units were silently destroyed, with no [com.tracel.model.transaction.Transaction]
     * ever logged to say so at all. Checking first makes a failed withdrawal a true no-op, which
     * is what every caller already assumed it was.
     */
    public suspend fun withdraw(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): List<LotPortion> = atomically {
        val available = repo.totalOf(holder, itemKey)
        check(available >= quantity.raw) {
            "insufficient balance at $holder for $itemKey: needed ${quantity.raw}, have $available"
        }

        var stillNeeded = quantity.raw
        val taken = mutableListOf<LotPortion>()
        var page = FIRST_PAGE

        while (stillNeeded > 0) {
            val queue = repo.accountQueue(holder, itemKey, limit = page)
            check(queue.isNotEmpty()) {
                "insufficient balance at $holder for $itemKey: needed ${quantity.raw}, short by $stillNeeded"
            }

            for ((_, lot, remaining) in queue) {
                if (stillNeeded <= 0) break
                val inThisLot = remaining.raw

                if (inThisLot <= stillNeeded) {
                    // This lot is entirely consumed, so it is retired from the account and added to the withdrawal
                    repo.remove(holder, lot.id)
                    taken += LotPortion(lot.id, remaining)
                    stillNeeded -= inThisLot
                } else {
                    // Two new lots are created: one for the portion taken, one for the portion kept
                    val takenQty = Quantity(stillNeeded)
                    val keptQty = Quantity(inThisLot - stillNeeded)
                    val takenLot = repo.createLot(itemKey, takenQty, txn)
                    val keptLot = repo.createLot(itemKey, keptQty, txn)
                    repo.recordEdge(LotEdge.Split(takenLot.id, lot.id, takenQty))
                    repo.recordEdge(LotEdge.Split(keptLot.id, lot.id, keptQty))

                    // The remaining portion occupies the same place in the queue — for all who come after,
                    // it remains the "oldest" lot in this account.
                    repo.replace(holder, lot.id, keptLot.id, keptQty)
                    taken += LotPortion(takenLot.id, takenQty)
                    stillNeeded = 0
                }
            }

            page = (page * 2).coerceAtMost(MAX_PAGE)
        }

        taken
    }

    /**
     * Withdraws [lotId] specifically, wherever it sits in [holder]'s queue —
     * skipping FIFO order entirely.
     *
     * A rollback's `Take` step needs this, not the ordinary [withdraw]: after
     * an `Unmake` restores a craft's ingredients, the traced lot and an untouched
     * sibling both sit in the same account, and a plain FIFO withdrawal could easily
     * grab the sibling instead.
     *
     * Both would leave the same total behind, which is exactly the kind of bug
     * a test that only checks quantities would miss — the two are only
     * interchangeable in count, not in which material a rollback is actually
     * supposed to reclaim.
     */
    public suspend fun withdrawExact(holder: HolderId, lotId: LotId): LotPortion = atomically {
        val remaining = repo.placementOf(holder, lotId)?.remaining
            ?: error("lot $lotId is not currently placed at $holder")
        repo.remove(holder, lotId)
        LotPortion(lotId, remaining)
    }

    /** Places previously-withdrawn portions at [holder] — the other half of a move. */
    public suspend fun deposit(holder: HolderId, portions: List<LotPortion>): Unit = atomically {
        for ((lotId, quantity) in portions) repo.place(holder, lotId, quantity)
    }

    /**
     * Withdraws and deposits at the matching `HolderId.Sink` — not nowhere.
     *
     * A sink is still a holder as far as this ledger is concerned, exactly
     * like a real one; that is what lets `RollbackPlanner` treat "this lot's
     * material is sitting in lava" and "this lot's material is sitting in a
     * chest" as the same kind of question, one that just happens to have a
     * different answer. [LotLedger.census] is what excludes sinks from "real"
     * counts — a burned unit still exists in the ledger's own bookkeeping,
     * but not in the game world.
     */
    public suspend fun burn(holder: HolderId, itemKey: ItemKey, quantity: Quantity, reason: SinkKind, txn: TxnId): List<LotPortion> =
        atomically {
            val portions = withdraw(holder, itemKey, quantity, txn)
            deposit(HolderId.Sink(reason), portions)
            portions
        }

    /** Withdraw immediately followed by deposit — a plain relocation. */
    public suspend fun move(from: HolderId, to: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): List<LotPortion> =
        atomically {
            val portions = withdraw(from, itemKey, quantity, txn)
            deposit(to, portions)
            portions
        }

    /**
     * Re-places a lot that was previously fully withdrawn, without creating a
     * new one. This is the mechanical half of undoing a craft: the consumed
     * ingredient lots still exist, they were just sitting nowhere — restoring
     * them is exactly like [mint], except the lot already has a history.
     */
    public suspend fun restore(holder: HolderId, lotId: LotId, quantity: Quantity) {
        repo.place(holder, lotId, quantity)
    }

    /**
     * Consumes [ingredients] and produces one [product], recording a
     * [LotEdge.Transform] from every consumed portion to the new output lot.
     * Those edges are what let a rollback later work out exactly how many
     * units of a traced ingredient a crafted item is holding — see
     * `RollbackPlanner` for why that matters.
     */
    public suspend fun craft(ingredients: List<Ingredient>, product: Product, txn: TxnId): Lot = atomically {
        val consumed = ingredients.flatMap { withdraw(it.holder, it.itemKey, it.quantity, txn) }
        val outputLot = repo.createLot(product.itemKey, product.quantity, txn)
        repo.place(product.holder, outputLot.id, product.quantity)
        for ((lotId, quantity) in consumed) {
            repo.recordEdge(LotEdge.Transform(outputLot.id, lotId, quantity, txn, product.holder))
        }
        outputLot
    }

    /**
     * Mints a replacement for a lot that could not be physically recovered
     * during a rollback (burned in lava, consumed as fuel, ...), recording a
     * [LotEdge.Compensate] so the mint stays traceable to what it stands in for.
     */
    public suspend fun compensate(holder: HolderId, originalLotId: LotId, quantity: Quantity, txn: TxnId, job: RollbackJobId): Lot =
        atomically {
            val itemKey = repo.lot(originalLotId).itemKey
            val lot = repo.createLot(itemKey, quantity, txn)
            repo.place(holder, lot.id, quantity)
            repo.recordEdge(LotEdge.Compensate(lot.id, originalLotId, quantity, job))
            lot
        }

    /** The item key of [lotId], or throws if it does not exist. */
    public suspend fun itemKeyOf(lotId: LotId): ItemKey = repo.lot(lotId).itemKey

    /** The quantity of [lotId], or throws if it does not exist. */
    public suspend fun quantityOf(lotId: LotId): Quantity = repo.lot(lotId).quantity

    /** Where [lotId] currently sits, or `null` if nothing places it anywhere right now. */
    public suspend fun currentHolderOf(lotId: LotId): HolderId? = repo.currentHolderOf(lotId)

    /** Sum of everything currently placed at [holder] for [itemKey], or `null` if there is none. */
    public suspend fun totalAt(holder: HolderId, itemKey: ItemKey): Quantity? =
        repo.totalOf(holder, itemKey).takeIf { it > 0 }?.let(::Quantity)

    /**
     * Everything [holder] is currently believed to hold, summed by item key.
     * Lots with zero remaining are omitted.
     */
    public suspend fun totalsAt(holder: HolderId): Map<ItemKey, Quantity> =
        repo.totalsAt(holder).filterValues { it > 0 }.mapValues { (_, total) -> Quantity(total) }

    /**
     * How many units of [itemKey] exist in the game world right now — real
     * holders and escrow (material mid-rollback, still real, just in transit),
     * but not `Source` or `Sink`: nothing physically sits at those, they are
     * bookkeeping for "appeared from nowhere" and "destroyed", not places.
     *
     * Counting them would make every burn and every mint invisible to this
     * number, which defeats the point of having it — I4 is exactly the claim
     * that this number only ever changes through an explicit mint or burn.
     */
    public suspend fun census(itemKey: ItemKey): Long =
        repo.allPlacements(itemKey)
            .filterNot { it.holder is HolderId.Source || it.holder is HolderId.Sink }
            .sumOf { it.remaining.raw }

    /**
     * Removes [lotId]'s placement at [holder] outright, bypassing the normal
     * FIFO withdrawal scan.
     *
     * Used only to destroy a specific known lot (an unmade craft's output) —
     * never to take "some amount of an item key", which is what [withdraw] is for.
     *
     * Requires that [lotId] is still placed under its own id, i.e. it was never
     * itself partially withdrawn since creation; `RollbackPlanner` guarantees that
     * before this is ever called.
     */
    public suspend fun destroy(holder: HolderId, lotId: LotId) {
        repo.remove(holder, lotId)
    }

    private companion object {
        /** How much of an account queue a withdrawal reads before it asks for more. */
        const val FIRST_PAGE = 16

        /** Where the doubling stops — a page big enough that another round trip is the cheap part. */
        const val MAX_PAGE = 4096
    }
}
