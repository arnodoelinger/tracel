package com.tracel.tests.support

import com.tracel.engine.ledger.InMemoryLotRepository
import com.tracel.engine.ledger.LotLedger
import com.tracel.model.id.TxnId

/**
 * A fresh ledger plus a monotonic transaction-id source, so tests do not have
 * to hand-roll either.
 */
class LedgerHarness {
    val repo: InMemoryLotRepository = InMemoryLotRepository()
    val ledger: LotLedger = LotLedger(repo)

    private var nextTxnRaw = 1L

    fun nextTxn(): TxnId = TxnId(nextTxnRaw++)
}
