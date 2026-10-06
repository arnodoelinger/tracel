package com.tracel.engine.rollback.involution.apply

import com.tracel.model.transaction.CauseKind
import com.tracel.annotations.RequiresLease
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.LotPortion
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.lease.Lease
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import com.tracel.platform.storage.UnitOfWork
import com.tracel.engine.rollback.involution.plan.InvolutionStep

/**
 * Physically applies one [InvolutionStep] against the ledger.
 *
 * Takes a [Lease] for the same reason [JournalExecutor] does:
 * undoing a job touches exactly the lots the original rollback touched, and nothing else may
 * be mutating them concurrently while that happens.
 */
public class InvolutionExecutor(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextSeq: suspend () -> Seq,
) : UnitOfWork by ledger {
    /**
     * Checks whether all steps can be applied without overdrawing any holder.
     *
     * Steps are checked in order, so a later step can spend items returned by an earlier one.
     *
     * The check is done before applying anything. Otherwise, a failed step could leave the undo only
     * partially applied. We don't want that.
     *
     * [InvolutionStep.Remake] spends its ingredients where [LotLedger.recraft] takes them: at the product's holder.
     */
    @RequiresLease
    public suspend fun checkSatisfiable(lease: Lease, steps: List<InvolutionStep>): Unit = atomically {
        val pending = mutableMapOf<Pair<HolderId, ItemKey>, Long>()
        val known = mutableMapOf<Pair<HolderId, ItemKey>, Long>()

        /** Balance of an [itemKey] in a [holder]. */
        suspend fun balanceOf(holder: HolderId, itemKey: ItemKey): Long {
            val account = holder to itemKey
            val actual = known[account] ?: (ledger.totalAt(holder, itemKey)?.raw ?: 0L).also { known[account] = it }
            return actual + pending.getOrDefault(account, 0L)
        }

        /** Debit [amount] of [itemKey] from [holder]. */
        suspend fun debit(holder: HolderId, itemKey: ItemKey, amount: Long) {
            val available = balanceOf(holder, itemKey)
            check(available >= amount) {
                "insufficient balance at $holder for $itemKey: needed $amount, have $available"
            }
            pending.merge(holder to itemKey, -amount, Long::plus)
        }

        for (step in steps) {
            when (step) {
                is InvolutionStep.Return -> {
                    debit(step.from, step.itemKey, step.quantity.raw)
                    pending.merge(step.to to step.itemKey, step.quantity.raw, Long::plus)
                }

                is InvolutionStep.Retract -> debit(step.from, step.itemKey, step.quantity.raw)
                is InvolutionStep.Remake -> {
                    for ((_, itemKey, quantity) in step.inputs) debit(step.product.holder, itemKey, quantity.raw)
                    for ((_, quantity, holder) in step.outputs) pending.merge(
                        holder to step.product.itemKey,
                        quantity.raw,
                        Long::plus
                    )
                }
            }
        }
    }

    /** Apply an [InvolutionStep] to the ledger. */
    @RequiresLease
    public suspend fun apply(lease: Lease, step: InvolutionStep, txn: TxnId): Unit = atomically {
        val flows = when (step) {
            is InvolutionStep.Return -> {
                ledger.moveBack(step.from, step.to, step.lotId, step.itemKey, step.quantity, txn)
                listOf(Flow(step.itemKey, step.quantity, step.from, step.to, FlowKind.MOVE))
            }

            is InvolutionStep.Retract -> {
                // The mint's own lot
                val minted = checkNotNull(step.compensationLot) {
                    "the lot job ${lease.job} minted for ${step.originalLot} is gone; burning by FIFO would be a guess"
                }
                ledger.burnBack(step.from, minted, step.itemKey, step.quantity, SinkKind.ROLLBACK_BURN, txn)
                step.originalLot?.let { ledger.uncompensate(it, lease.job) }
                listOf(
                    Flow(
                        step.itemKey,
                        step.quantity,
                        step.from,
                        HolderId.Sink(SinkKind.ROLLBACK_BURN),
                        FlowKind.BURN
                    )
                )
            }

            is InvolutionStep.Remake -> {
                val holder = step.product.holder
                ledger.recraft(
                    holder,
                    step.outputs.map { it.holder to LotPortion(it.lotId, it.quantity) },
                    step.inputs.map { it.lotId to it.quantity },
                    txn,
                )

                step.inputs.map {
                    Flow(it.itemKey, it.quantity, holder, HolderId.Sink(SinkKind.CRAFT_CONSUME), FlowKind.TRANSFORM_IN)
                } + Flow(
                    step.product.itemKey,
                    step.product.quantity,
                    HolderId.Source(SourceKind.CRAFT),
                    holder,
                    FlowKind.TRANSFORM_OUT
                )
            }
        }

        log.append(
            Transaction(
                txn,
                nextSeq(),
                System.currentTimeMillis(),
                CauseKind.INVOLUTION,
                causedBy = null,
                flows
            )
        )
    }
}
