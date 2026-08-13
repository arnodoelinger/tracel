package com.tracel.engine.balance

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey

/**
 * Applies [flow] to this ledger — the mechanical half of what [TransactionBalancer] produces.
 */
public fun LotLedger.apply(flow: Flow, txn: TxnId) {
    when (flow.kind) {
        FlowKind.MOVE -> move(flow.source, flow.destination, flow.itemKey, flow.quantity, txn)
        FlowKind.MINT -> mint(flow.destination, flow.itemKey, flow.quantity, txn)
        FlowKind.BURN -> burn(flow.source, flow.itemKey, flow.quantity, (flow.destination as HolderId.Sink).kind, txn)
        FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT ->
            error("TRANSFORM flows come from LotLedger.craft() directly, not from applying a Flow generically")
    }
}

/**
 * Throws unless every withdrawal [flows] implies can actually be satisfied — before any of them
 * has mutated anything.
 *
 * Simulates the flows in order rather than summing per source, so a flow drawing on material an
 * earlier flow in the same transaction deposited is judged on the balance it would actually see.
 */
public fun LotLedger.checkAllWithdrawalsSatisfiable(flows: List<Flow>) {
    val simulated = mutableMapOf<Pair<HolderId, ItemKey>, Long>()
    fun balanceOf(holder: HolderId, itemKey: ItemKey): Long =
        simulated.getOrPut(holder to itemKey) { totalAt(holder, itemKey)?.raw ?: 0L }

    fun credit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        simulated[holder to itemKey] = balanceOf(holder, itemKey) + amount
    }

    fun debit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        val available = balanceOf(holder, itemKey)
        check(available >= amount) {
            "insufficient balance at $holder for $itemKey: needed $amount, have $available"
        }
        simulated[holder to itemKey] = available - amount
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
