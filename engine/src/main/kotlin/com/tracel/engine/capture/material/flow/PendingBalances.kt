package com.tracel.engine.capture.material.flow

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * What a list of flows would leave in each account if they were applied in order, without applying them.
 *
 * The ledger is read once per account; everything after that is the flows' own credits and debits.
 */
internal class PendingBalances(private val ledger: LotLedger) {
    private val pending = mutableMapOf<Pair<HolderId, ItemKey>, Long>()
    private val known = mutableMapOf<Pair<HolderId, ItemKey>, Long>()

    /** @return the balance of [holder] and [itemKey], including any pending credits. */
    suspend fun balanceOf(holder: HolderId, itemKey: ItemKey): Long {
        val account = holder to itemKey
        val actual = known[account] ?: (ledger.totalAt(holder, itemKey)?.raw ?: 0L).also { known[account] = it }
        return actual + pending.getOrDefault(account, 0L)
    }

    /** Adds [amount], which is negative for a debit, to the pending balance of [holder] and [itemKey]. */
    fun add(holder: HolderId, itemKey: ItemKey, amount: Long) {
        pending.merge(holder to itemKey, amount, Long::plus)
    }
}
