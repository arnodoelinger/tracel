package com.tracel.tests.support

import com.tracel.engine.ledger.InMemoryLotRepository
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ownership.InMemoryLotLeaseRegistry
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.ownership.LotLease
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * A fresh ledger plus a monotonic transaction-id source, so tests do not have
 * to hand-roll either.
 */
class LedgerHarness {
    val repo: InMemoryLotRepository = InMemoryLotRepository()
    val ledger: LotLedger = LotLedger(repo)
    val leases: InMemoryLotLeaseRegistry = InMemoryLotLeaseRegistry()

    private var nextTxnRaw = 1L

    fun nextTxn(): TxnId = TxnId(nextTxnRaw++)

    /** Acquires a lease over everything [plan] touches, failing the test loudly if it is denied. */
    fun acquireLease(job: RollbackJobId, plan: RollbackPlan): LotLease =
        when (val acquisition = leases.acquire(job, plan.touchedLots)) {
            is LeaseAcquisition.Granted -> acquisition.lease
            is LeaseAcquisition.Denied -> error("lease denied for job $job: ${acquisition.conflicts}")
        }
}
