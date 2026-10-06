package com.tracel.tests.support

import com.tracel.engine.ledger.repository.memory.InMemoryLotRepository
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.memory.InMemoryTransactionLog
import com.tracel.engine.rollback.lease.memory.InMemoryLeases
import com.tracel.engine.rollback.lease.acquisition.LeaseAcquisition
import com.tracel.engine.rollback.lease.Lease
import com.tracel.engine.rollback.job.record.memory.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId

class LedgerHarness {
    val repo: InMemoryLotRepository = InMemoryLotRepository()
    val ledger: LotLedger = LotLedger(repo)
    val leases: InMemoryLeases = InMemoryLeases()
    val log: InMemoryTransactionLog = InMemoryTransactionLog(NamespacedNames)
    val jobs: InMemoryRollbackJobRepository = InMemoryRollbackJobRepository()

    private var nextTxnRaw = 1L
    private var nextSeqRaw = 1L

    fun nextTxn(): TxnId = TxnId(nextTxnRaw++)
    fun nextSeq(): Seq = Seq(nextSeqRaw++)

    /** Acquires a lease over everything [plan] touches, failing the test loudly if it is denied. */
    suspend fun acquireLease(job: RollbackJobId, plan: RollbackPlan): Lease =
        when (val acquisition = leases.acquire(job, plan.touchedLots)) {
            is LeaseAcquisition.Granted -> acquisition.lease
            is LeaseAcquisition.Denied -> error("lease denied for job $job: ${acquisition.conflicts}")
        }
}
