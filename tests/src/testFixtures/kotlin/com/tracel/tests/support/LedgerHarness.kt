package com.tracel.tests.support

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.ledger.repository.memory.InMemoryLotRepository
import com.tracel.engine.log.memory.InMemoryTransactionLog
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.involution.apply.InvolutionExecutor
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.job.RollbackOutcome
import com.tracel.engine.rollback.job.record.memory.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.journal.Journal
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.lease.Lease
import com.tracel.engine.rollback.lease.acquisition.LeaseAcquisition
import com.tracel.engine.rollback.lease.memory.InMemoryLeases
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId

class LedgerHarness {
    val repo: InMemoryLotRepository = InMemoryLotRepository()
    val ledger: LotLedger = LotLedger(repo)
    val leases: InMemoryLeases = InMemoryLeases()
    val log: InMemoryTransactionLog = InMemoryTransactionLog(NamespacedNames)
    val jobs: InMemoryRollbackJobRepository = InMemoryRollbackJobRepository()

    private var nextTxnRaw = 1L
    private var nextSeqRaw = 1L
    private var nextJobRaw = 1L

    fun nextTxn(): TxnId = TxnId(nextTxnRaw++)
    fun nextSeq(): Seq = Seq(nextSeqRaw++)
    fun nextJob(): RollbackJobId = RollbackJobId(nextJobRaw++)

    suspend fun mint(at: HolderId, item: ItemKey, amount: Long): Lot =
        ledger.mint(at, item, Quantity(amount), nextTxn())

    suspend fun move(from: HolderId, to: HolderId, item: ItemKey, amount: Long) {
        ledger.move(from, to, item, Quantity(amount), nextTxn())
    }

    suspend fun burn(at: HolderId, item: ItemKey, amount: Long, reason: SinkKind = SinkKind.HAZARD) {
        ledger.burn(at, item, Quantity(amount), reason, nextTxn())
    }

    suspend fun craft(at: HolderId, input: ItemKey, used: Long, output: ItemKey, produced: Long) {
        ledger.craft(
            listOf(Ingredient(at, input, Quantity(used))),
            Product(at, output, Quantity(produced)),
            nextTxn(),
        )
    }

    suspend fun count(at: HolderId, item: ItemKey): Long = ledger.totalAt(at, item)?.raw ?: 0L

    suspend fun census(item: ItemKey): Long = ledger.census(item)

    fun planner(
        structural: Boolean = true,
        vanished: Set<HolderId> = emptySet(),
        maxTransformDepth: Int = 32,
    ): RollbackPlanner = RollbackPlanner(
        repo,
        { true },
        maxTransformDepth = maxTransformDepth,
        vanished = vanished,
        structural = structural,
    )

    fun journalExecutor(journal: Journal = InMemoryJournal()): JournalExecutor =
        JournalExecutor(RollbackExecutor(ledger, log, ::nextSeq), journal, leases, ::nextTxn)

    fun rollbackCoordinator(
        journalExecutor: JournalExecutor = journalExecutor(),
        ledgerVersion: (suspend () -> Long)? = null,
    ): RollbackJobCoordinator =
        RollbackJobCoordinator(repo, { true }, leases, journalExecutor, jobs, ledgerVersion = ledgerVersion)

    fun involutionCoordinator(journal: Journal = InMemoryJournal()): InvolutionJobCoordinator =
        InvolutionJobCoordinator(jobs, repo, leases, InvolutionExecutor(ledger, log, ::nextSeq), journal, ::nextTxn)

    suspend fun rollback(roots: List<LotId>, target: RollbackTarget): AppliedRollback {
        val job = nextJob()
        val outcome = rollbackCoordinator().run(job, roots, target)
        return AppliedRollback(
            job,
            (outcome as? RollbackOutcome.Applied ?: error("rollback not applied: $outcome")).plan
        )
    }

    suspend fun rollback(roots: List<LotId>, to: HolderId): AppliedRollback =
        rollback(roots, RollbackTarget.Uniform(to))

    suspend fun undo(job: RollbackJobId): InvolutionOutcome.Undone =
        involutionCoordinator().undo(job) as? InvolutionOutcome.Undone ?: error("undo of $job did not run")

    suspend fun acquireLease(job: RollbackJobId, plan: RollbackPlan): Lease =
        when (val acquisition = leases.acquire(job, plan.touchedLots)) {
            is LeaseAcquisition.Granted -> acquisition.lease
            is LeaseAcquisition.Denied -> error("lease denied for job $job: ${acquisition.conflicts}")
        }
}

class AppliedRollback(val job: RollbackJobId, val plan: RollbackPlan)
