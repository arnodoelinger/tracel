package com.tracel.engine.capture

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.LotPortion
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey

/** The ledger never saw this arrive, and nobody can say where it came from. */
public val UNATTRIBUTED_SOURCE: HolderId.Source = HolderId.Source(SourceKind.UNATTRIBUTED)

/**
 * Applies one [flow] to the ledger.
 *
 * Returns the portions that actually moved. The flow says how many, the lots say which ones.
 *
 * Crafts are in [LotLedger.craft].
 *
 * @see [Flow]
 * @see [LotLedger.craft]
 */
public suspend fun LotLedger.apply(flow: Flow, txn: TxnId): List<LotPortion> =
    when (flow.kind) {
        FlowKind.MOVE -> move(flow.source, flow.destination, flow.itemKey, flow.quantity, txn)
        FlowKind.MINT -> listOf(LotPortion(mint(flow.destination, flow.itemKey, flow.quantity, txn).id, flow.quantity))
        FlowKind.BURN -> burn(flow.source, flow.itemKey, flow.quantity, (flow.destination as HolderId.Sink).kind, txn)
        FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT ->
            error("TRANSFORM flows come from LotLedger.craft() directly, not from applying a Flow generically")
    }

/**
 * Prepends mint flows for every withdrawal in [flows] that the ledger cannot satisfy.
 *
 * The world is treated as the source of truth. If material left a holder, it must have been there;
 * the ledger simply never saw it arrive.
 *
 * That can happen with:
 * - Pre-plugin items
 * - Untracked transfers
 * - Snapshots that briefly lag reality
 * - Because of my fault
 *
 * Minting restores the missing balance (so the withdrawal can be recorded instead of disappearing).
 *
 * The caller decides which holders are safe to mint into via [mintable]. The important distinction
 * is between "not known yet" and "will never be known"-minting into the former duplicates material
 * once the delayed credit eventually arrives.
 *
 * Flows are processed in order, allowing later withdrawals to spend earlier deposits before minting
 * any remaining shortfall.
 */
public suspend fun LotLedger.shortfallMints(
    flows: List<Flow>,
    mintable: (HolderId) -> Boolean,
): List<Flow> = atomically {
    val pending = mutableMapOf<Pair<HolderId, ItemKey>, Long>()
    val known = mutableMapOf<Pair<HolderId, ItemKey>, Long>()
    val mints = mutableListOf<Flow>()

    /** @return the balance of [holder] and [itemKey], including any pending credits. */
    suspend fun balanceOf(holder: HolderId, itemKey: ItemKey): Long {
        val account = holder to itemKey
        val actual = known[account] ?: (totalAt(holder, itemKey)?.raw ?: 0L).also { known[account] = it }
        return actual + pending.getOrDefault(account, 0L)
    }

    /** Debits [holder] and [itemKey] by [amount], minting if necessary. */
    suspend fun debit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        if (holder !is HolderId.Source && holder !is HolderId.Sink && mintable(holder)) {
            val missing = amount - balanceOf(holder, itemKey)
            if (missing > 0L) {
                mints += Flow(itemKey, Quantity(missing), UNATTRIBUTED_SOURCE, holder, FlowKind.MINT)
                pending.merge(holder to itemKey, missing, Long::plus)
            }
        }
        pending.merge(holder to itemKey, -amount, Long::plus)
    }

    for ((itemKey, quantity, source, destination, kind) in flows) {
        when (kind) {
            FlowKind.MOVE -> {
                debit(source, itemKey, quantity.raw)
                pending.merge(destination to itemKey, quantity.raw, Long::plus)
            }

            FlowKind.BURN -> debit(source, itemKey, quantity.raw)
            FlowKind.MINT -> pending.merge(destination to itemKey, quantity.raw, Long::plus)
            FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT ->
                error("TRANSFORM flows come from LotLedger.craft() directly, not from applying a Flow generically")
        }
    }
    mints
}

/**
 * Fails the whole set if any withdrawal would overdraw, without mutating the ledger.
 *
 * Walks [flows] in order: a later move can spend what an earlier one just deposited.
 */
public suspend fun LotLedger.checkAllWithdrawalsSatisfiable(flows: List<Flow>): Unit = atomically {
    val pending = mutableMapOf<Pair<HolderId, ItemKey>, Long>()
    val known = mutableMapOf<Pair<HolderId, ItemKey>, Long>()

    /** @return the balance of [holder] and [itemKey], including any pending credits. */
    suspend fun balanceOf(holder: HolderId, itemKey: ItemKey): Long {
        val account = holder to itemKey
        val actual = known[account] ?: (totalAt(holder, itemKey)?.raw ?: 0L).also { known[account] = it }
        return actual + pending.getOrDefault(account, 0L)
    }

    /** Adds [amount] to the pending balance of [holder] and [itemKey]. */
    fun credit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        pending.merge(holder to itemKey, amount, Long::plus)
    }

    /** Subtracts [amount] from the pending balance of [holder] and [itemKey]. */
    suspend fun debit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        val available = balanceOf(holder, itemKey)
        check(available >= amount) {
            "insufficient balance at $holder for $itemKey: needed $amount, have $available"
        }
        pending.merge(holder to itemKey, -amount, Long::plus)
    }

    for ((itemKey, quantity, source, destination, kind) in flows) {
        when (kind) {
            FlowKind.MOVE -> {
                debit(source, itemKey, quantity.raw)
                credit(destination, itemKey, quantity.raw)
            }

            FlowKind.BURN -> debit(source, itemKey, quantity.raw)
            FlowKind.MINT -> credit(destination, itemKey, quantity.raw)
            FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT ->
                error("TRANSFORM flows come from LotLedger.craft() directly, not from applying a Flow generically")
        }
    }
}
