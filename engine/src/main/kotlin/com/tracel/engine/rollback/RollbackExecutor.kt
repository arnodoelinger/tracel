package com.tracel.engine.rollback

import com.tracel.annotations.CauseKind
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction

/**
 * Physically applies one [RollbackStep] against the ledger.
 */
public class RollbackExecutor(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextSeq: () -> Seq,
) {
    public fun apply(job: RollbackJobId, step: RollbackStep, txn: TxnId) {
        val escrow = HolderId.Escrow(job)
        val flows = when (step) {
            is RollbackStep.Take -> {
                val itemKey = ledger.itemKeyOf(step.lotId)
                ledger.deposit(escrow, listOf(ledger.withdrawExact(step.holder, step.lotId)))
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

        log.append(Transaction(txn, nextSeq(), System.currentTimeMillis(), CauseKind.ROLLBACK, causedBy = null, flows))
    }

    /** Moves everything the job collected in escrow to its final destination — the barrier between take and restore. */
    public fun release(job: RollbackJobId, restoreTo: HolderId, itemKeys: Set<ItemKey>, txn: TxnId) {
        val escrow = HolderId.Escrow(job)
        val flows = mutableListOf<Flow>()
        for (itemKey in itemKeys) {
            val total = ledger.totalAt(escrow, itemKey) ?: continue
            ledger.deposit(restoreTo, ledger.withdraw(escrow, itemKey, total, txn))
            flows += Flow(itemKey, total, escrow, restoreTo, FlowKind.MOVE)
        }
        if (flows.isEmpty()) return

        log.append(Transaction(txn, nextSeq(), System.currentTimeMillis(), CauseKind.ROLLBACK, causedBy = null, flows))
    }

    /** Every item key a [release] for this plan will need to look for in escrow. */
    public fun escrowItemKeys(plan: RollbackPlan): Set<ItemKey> =
        plan.steps.filterIsInstance<RollbackStep.Take>().mapTo(mutableSetOf()) { ledger.itemKeyOf(it.lotId) } +
            plan.steps.filterIsInstance<RollbackStep.Mint>().mapTo(mutableSetOf()) { ledger.itemKeyOf(it.lotId) } +
            plan.steps.filterIsInstance<RollbackStep.Debt>().mapTo(mutableSetOf()) { ledger.itemKeyOf(it.lotId) }
}
