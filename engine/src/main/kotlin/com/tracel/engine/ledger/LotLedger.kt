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
 * come from" a question with a reproducible answer at all.
 *
 * A lot's identity survives it moving between holders: relocating an entire
 * placement keeps the same lot id. Only a partial withdrawal — taking less
 * than an account's oldest batch holds — creates two new lots (the part taken,
 * the part left behind), each linked back to the original by a
 * [LotEdge.Split]. That is the one and only place new lots get created out of
 * an existing one; everything else in this class is bookkeeping around it.
 *
 * @see <a href="https://en.wikipedia.org/wiki/FIFO_and_LIFO_accounting">FIFO and LIFO accounting</a>
 */
public class LotLedger(private val repo: LotRepository) {
    /** A brand-new batch enters the ledger with no prior lot — e.g. a mob drop, worldgen, a rollback compensation. */
    public fun mint(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): Lot {
        val lot = repo.createLot(itemKey, quantity, txn)
        repo.place(holder, lot.id, quantity)
        return lot
    }

    /**
     * Takes [quantity] of [itemKey] out of [holder], oldest lots first,
     * splitting the last lot it has to touch. Throws if the account does not
     * hold enough — callers are expected to check first, since a shortfall
     * here means the caller's own bookkeeping is wrong, not that this is a
     * normal, recoverable outcome.
     */
    public fun withdraw(holder: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): List<LotPortion> {
        var stillNeeded = quantity.raw
        val taken = mutableListOf<LotPortion>()

        for ((_, lot, remaining) in repo.accountQueue(holder, itemKey)) {
            if (stillNeeded <= 0) break
            val available = remaining.raw

            if (available <= stillNeeded) {
                // This lot is entirely consumed, so it is retired from the account and added to the withdrawal
                repo.remove(holder, lot.id)
                taken += LotPortion(lot.id, remaining)
                stillNeeded -= available
            } else {
                // Two new lots are created: one for the portion taken, one for the portion kept
                val takenQty = Quantity(stillNeeded)
                val keptQty = Quantity(available - stillNeeded)
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

        check(stillNeeded <= 0) {
            "insufficient balance at $holder for $itemKey: needed ${quantity.raw}, short by $stillNeeded"
        }
        return taken
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
    public fun withdrawExact(holder: HolderId, lotId: LotId): LotPortion {
        val itemKey = repo.lot(lotId).itemKey
        val remaining = repo.accountQueue(holder, itemKey).firstOrNull { it.lot.id == lotId }?.remaining
            ?: error("lot $lotId is not currently placed at $holder")
        repo.remove(holder, lotId)
        return LotPortion(lotId, remaining)
    }

    /** Places previously-withdrawn portions at [holder] — the other half of a move. */
    public fun deposit(holder: HolderId, portions: List<LotPortion>) {
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
    public fun burn(holder: HolderId, itemKey: ItemKey, quantity: Quantity, reason: SinkKind, txn: TxnId): List<LotPortion> {
        val portions = withdraw(holder, itemKey, quantity, txn)
        deposit(HolderId.Sink(reason), portions)
        return portions
    }

    /** Withdraw immediately followed by deposit — a plain relocation. */
    public fun move(from: HolderId, to: HolderId, itemKey: ItemKey, quantity: Quantity, txn: TxnId): List<LotPortion> {
        val portions = withdraw(from, itemKey, quantity, txn)
        deposit(to, portions)
        return portions
    }

    /**
     * Re-places a lot that was previously fully withdrawn, without creating a
     * new one. This is the mechanical half of undoing a craft: the consumed
     * ingredient lots still exist, they were just sitting nowhere — restoring
     * them is exactly like [mint], except the lot already has a history.
     */
    public fun restore(holder: HolderId, lotId: LotId, quantity: Quantity) {
        repo.place(holder, lotId, quantity)
    }

    /**
     * Consumes [ingredients] and produces one [product], recording a
     * [LotEdge.Transform] from every consumed portion to the new output lot.
     * Those edges are what let a rollback later work out exactly how many
     * units of a traced ingredient a crafted item is holding — see
     * `RollbackPlanner` for why that matters.
     */
    public fun craft(ingredients: List<Ingredient>, product: Product, txn: TxnId): Lot {
        val consumed = ingredients.flatMap { withdraw(it.holder, it.itemKey, it.quantity, txn) }
        val outputLot = repo.createLot(product.itemKey, product.quantity, txn)
        repo.place(product.holder, outputLot.id, product.quantity)
        for ((lotId, quantity) in consumed) {
            repo.recordEdge(LotEdge.Transform(outputLot.id, lotId, quantity, txn, product.holder))
        }
        return outputLot
    }

    /**
     * Mints a replacement for a lot that could not be physically recovered
     * during a rollback (burned in lava, consumed as fuel, ...), recording a
     * [LotEdge.Compensate] so the mint stays traceable to what it stands in for.
     */
    public fun compensate(holder: HolderId, originalLotId: LotId, quantity: Quantity, txn: TxnId, job: RollbackJobId): Lot {
        val itemKey = repo.lot(originalLotId).itemKey
        val lot = repo.createLot(itemKey, quantity, txn)
        repo.place(holder, lot.id, quantity)
        repo.recordEdge(LotEdge.Compensate(lot.id, originalLotId, quantity, job))
        return lot
    }

    /** The item key of [lotId], or throws if it does not exist. */
    public fun itemKeyOf(lotId: LotId): ItemKey = repo.lot(lotId).itemKey

    /** The quantity of [lotId], or throws if it does not exist. */
    public fun quantityOf(lotId: LotId): Quantity = repo.lot(lotId).quantity

    /** Where [lotId] currently sits, or `null` if nothing places it anywhere right now. */
    public fun currentHolderOf(lotId: LotId): HolderId? = repo.currentHolderOf(lotId)

    /** Sum of everything currently placed at [holder] for [itemKey], or `null` if there is none. */
    public fun totalAt(holder: HolderId, itemKey: ItemKey): Quantity? =
        repo.accountQueue(holder, itemKey).sumOf { it.remaining.raw }.takeIf { it > 0 }?.let(::Quantity)

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
    public fun census(itemKey: ItemKey): Long =
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
    public fun destroy(holder: HolderId, lotId: LotId) {
        repo.remove(holder, lotId)
    }
}
