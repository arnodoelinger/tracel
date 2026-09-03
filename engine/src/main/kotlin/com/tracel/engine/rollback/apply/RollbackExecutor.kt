package com.tracel.engine.rollback.apply

import com.tracel.annotations.CauseKind
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.escrowDeliveries
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.LotId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import com.tracel.platform.storage.UnitOfWork

/** Physically applies one [RollbackStep] against the ledger. */
public class RollbackExecutor(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextSeq: suspend () -> Seq,
) : UnitOfWork by ledger {
    /** Applies every one of [steps] and logs them as one transaction. */
    public suspend fun applyAll(
        job: RollbackJobId,
        steps: List<RollbackStep>,
        txn: TxnId,
        flowLimit: Int = MAX_FLOWS_PER_TRANSACTION,
        nextTxn: suspend () -> TxnId = { txn },
        plan: RollbackPlan? = null,
        target: RollbackTarget? = null,
    ): Unit = atomically {
        ledger.prefetchLots(lotsNamedBy(steps))

        val flows = ArrayList<Flow>(steps.size)
        for (step in steps) {
            val dest = if (plan != null && target != null) destinationOf(plan, target, step) else null
            flows += applyOne(job, step, txn, dest)
        }

        var from = 0
        var id = txn
        while (from < flows.size) {
            val until = minOf(from + flowLimit, flows.size)
            log.append(
                Transaction(id, nextSeq(), System.currentTimeMillis(), CauseKind.ROLLBACK, causedBy = null, flows.subList(from, until))
            )
            from = until
            if (from < flows.size) id = nextTxn()
        }
    }

    public suspend fun apply(job: RollbackJobId, step: RollbackStep, txn: TxnId): Unit = atomically {
        val flows = applyOne(job, step, txn, dest = null)
        log.append(Transaction(txn, nextSeq(), System.currentTimeMillis(), CauseKind.ROLLBACK, causedBy = null, flows))
    }

    private fun lotsNamedBy(steps: List<RollbackStep>): Set<LotId> {
        val ids = HashSet<LotId>(steps.size)
        for (step in steps) {
            when (step) {
                is RollbackStep.Take -> ids += step.lotId
                is RollbackStep.Mint -> ids += step.lotId
                is RollbackStep.Debt -> ids += step.lotId
                is RollbackStep.Unmake -> {
                    ids += step.outputLot
                    for (input in step.inputs) ids += input.lotId
                }
            }
        }
        return ids
    }

    /** The ledger half of one step. Logging is the caller's, so a batch can log once. */
    private suspend fun applyOne(job: RollbackJobId, step: RollbackStep, txn: TxnId, dest: HolderId?): List<Flow> {
        val escrow = dest ?: HolderId.Escrow(job)
        return when (step) {
            is RollbackStep.Take -> {
                val itemKey = ledger.itemKeyOf(step.lotId)
                if (dest != null) {
                    ledger.moveExact(step.holder, dest, step.lotId)
                } else {
                    ledger.deposit(escrow, listOf(ledger.withdrawExact(step.holder, step.lotId)))
                }
                listOf(Flow(itemKey, step.quantity, step.holder, escrow, FlowKind.MOVE))
            }

            is RollbackStep.Mint -> {
                val itemKey = ledger.itemKeyOf(step.lotId)
                ledger.compensate(escrow, step.lotId, step.quantity, txn, job)
                listOf(Flow(itemKey, step.quantity, HolderId.Source(SourceKind.ROLLBACK_MINT), escrow, FlowKind.MINT))
            }

            is RollbackStep.Debt -> {
                val itemKey = ledger.itemKeyOf(step.lotId)
                ledger.compensate(escrow, step.lotId, step.quantity, txn, job)
                listOf(Flow(itemKey, step.quantity, HolderId.Source(SourceKind.ROLLBACK_MINT), escrow, FlowKind.MINT))
            }

            is RollbackStep.Unmake -> {
                val outputKey = ledger.itemKeyOf(step.outputLot)
                val outputQty = ledger.quantityOf(step.outputLot)
                ledger.destroy(step.holder, step.outputLot)
                for ((lotId, quantity) in step.inputs) ledger.restore(step.holder, lotId, quantity)

                listOf(Flow(outputKey, outputQty, step.holder, HolderId.Sink(SinkKind.CRAFT_CONSUME), FlowKind.TRANSFORM_IN)) +
                    step.inputs.map { input ->
                        Flow(ledger.itemKeyOf(input.lotId), input.quantity, HolderId.Source(SourceKind.CRAFT), step.holder, FlowKind.TRANSFORM_OUT)
                    }
            }
        }
    }

    /**
     * Moves everything the job collected in escrow to where it belongs. It's the barrier between
     * taking and restoring.
     */
    public suspend fun release(job: RollbackJobId, plan: RollbackPlan, target: RollbackTarget, txn: TxnId): Unit = atomically {
        val escrow = HolderId.Escrow(job)
        val flows = mutableListOf<Flow>()

        val owedByItem = LinkedHashMap<ItemKey, MutableList<Pair<HolderId, Long>>>()
        for ((destination, owed) in escrowDeliveries(plan, target, ledger)) {
            for ((itemKey, wanted) in owed) {
                if (wanted <= 0) continue
                owedByItem.getOrPut(itemKey) { mutableListOf() } += destination to wanted
            }
        }

        for ((itemKey, owed) in owedByItem) {
            for ((destination, portions) in ledger.drain(escrow, itemKey, owed, txn)) {
                ledger.deposit(destination, portions)
                flows += Flow(itemKey, Quantity(portions.sumOf { it.quantity.raw }), escrow, destination, FlowKind.MOVE)
            }
        }

        if (flows.isNotEmpty()) {
            log.append(Transaction(txn, nextSeq(), System.currentTimeMillis(), CauseKind.ROLLBACK, causedBy = null, flows))
        }
    }

    private fun destinationOf(plan: RollbackPlan, target: RollbackTarget, step: RollbackStep): HolderId? = when (step) {
        is RollbackStep.Take -> target.destinationFor(plan, step.lotId)
        is RollbackStep.Mint -> target.destinationFor(plan, step.lotId)
        is RollbackStep.Debt -> target.destinationFor(plan, step.lotId)
        is RollbackStep.Unmake -> null
    }

    public companion object {
        public const val MAX_FLOWS_PER_TRANSACTION: Int = 8192
    }
}
