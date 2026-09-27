package com.tracel.engine.rollback.apply

import com.tracel.annotations.CauseKind
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.*
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import com.tracel.platform.storage.UnitOfWork

/** Physically applies one [RollbackStep] against the ledger. */
public class RollbackExecutor(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextSeq: suspend () -> Seq,
) : UnitOfWork by ledger {
    /**
     * Reads every lot [steps] name ahead, outside any unit: inside one, the whole storage lock waited on
     * those reads and the drainer with it.
     */
    public suspend fun prefetch(steps: List<RollbackStep>): Unit = ledger.prefetchLots(lotsNamedBy(steps))

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
                Transaction(
                    id,
                    nextSeq(),
                    System.currentTimeMillis(),
                    CauseKind.ROLLBACK,
                    causedBy = null,
                    flows.subList(from, until)
                )
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
                    for ((lotId) in step.outputs) ids += lotId
                    for ((lotId) in step.inputs) ids += lotId
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
                val moved = if (dest != null) {
                    ledger.moveExact(step.holder, dest, step.lotId)
                } else {
                    ledger.withdrawExact(step.holder, step.lotId).also { ledger.deposit(escrow, listOf(it)) }.quantity
                }
                check(moved == step.quantity) {
                    "lot ${step.lotId} holds ${moved.raw} at ${step.holder}, the plan expected ${step.quantity.raw}"
                }
                listOf(Flow(itemKey, moved, step.holder, escrow, FlowKind.MOVE))
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
                val outputKey = ledger.itemKeyOf(step.outputs.first().lotId)
                var outputQty = 0L

                // Throws for an output that moved since planning, before any ingredient comes back
                for ((lotId, holder) in step.outputs) outputQty += ledger.withdrawExact(holder, lotId).quantity.raw
                for ((lotId, quantity) in step.inputs) ledger.restore(step.holder, lotId, quantity)

                listOf(
                    Flow(
                        outputKey,
                        Quantity(outputQty),
                        step.holder,
                        HolderId.Sink(SinkKind.CRAFT_CONSUME),
                        FlowKind.TRANSFORM_IN
                    )
                ) +
                        step.inputs.map { input ->
                            Flow(
                                ledger.itemKeyOf(input.lotId),
                                input.quantity,
                                HolderId.Source(SourceKind.CRAFT),
                                step.holder,
                                FlowKind.TRANSFORM_OUT
                            )
                        }
            }
        }
    }

    /**
     * Moves everything the job collected in escrow to where it belongs. It's the barrier between
     * taking and restoring.
     */
    public suspend fun release(job: RollbackJobId, plan: RollbackPlan, target: RollbackTarget, txn: TxnId): Unit =
        atomically {
            val escrow = HolderId.Escrow(job)
            val moved = LinkedHashMap<Pair<HolderId, ItemKey>, Long>()

            for (step in plan.steps) {
                val traced = when (step) {
                    is RollbackStep.Take -> step.lotId
                    is RollbackStep.Mint -> step.lotId
                    is RollbackStep.Debt -> step.lotId
                    is RollbackStep.Unmake -> continue
                }
                val held = if (step is RollbackStep.Take) traced else ledger.compensationOf(traced, job) ?: continue

                // Directly delivered steps never went through escrow
                if (ledger.currentHolderOf(held) != escrow) continue
                val destination = target.destinationFor(plan, traced)
                ledger.moveExact(escrow, destination, held)
                moved.merge(destination to ledger.itemKeyOf(held), ledger.quantityOf(held).raw, Long::plus)
            }

            if (moved.isNotEmpty()) {
                val flows =
                    moved.map { (to, quantity) -> Flow(to.second, Quantity(quantity), escrow, to.first, FlowKind.MOVE) }
                log.append(
                    Transaction(
                        txn,
                        nextSeq(),
                        System.currentTimeMillis(),
                        CauseKind.ROLLBACK,
                        causedBy = null,
                        flows
                    )
                )
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
