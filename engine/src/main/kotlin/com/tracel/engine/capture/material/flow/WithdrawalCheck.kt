package com.tracel.engine.capture.material.flow

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.flow.Flow

/**
 * Fails the whole set if any withdrawal would overdraw, without mutating the ledger.
 *
 * Walks [flows] in order: a later move can spend what an earlier one just deposited.
 */
public suspend fun LotLedger.checkAllWithdrawalsSatisfiable(flows: List<Flow>): Unit = atomically {
    val balances = PendingBalances(this)

    flows.walk(
        debit = { holder, itemKey, amount ->
            val available = balances.balanceOf(holder, itemKey)
            check(available >= amount) {
                "insufficient balance at $holder for $itemKey: needed $amount, have $available"
            }
            balances.add(holder, itemKey, -amount)
        },
        credit = balances::add,
    )
}
