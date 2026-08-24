package com.tracel.engine.rollback.involution

import com.tracel.annotations.CauseKind
import com.tracel.engine.capture.craftFlows
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.ownership.LotLease
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.platform.storage.UnitOfWork

/**
 * Physically applies one [InvolutionStep] against the ledger.
 *
 * Takes a [LotLease] for the same reason [com.tracel.engine.journal.JournalExecutor] does:
 * undoing a job touches exactly the lots the original rollback touched, and nothing else may
 * be mutating them concurrently while that happens.
 */
public class InvolutionExecutor(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextSeq: suspend () -> Seq,
) : UnitOfWork by ledger {
    public suspend fun apply(lease: LotLease, step: InvolutionStep, txn: TxnId): Unit = atomically {
        val flows = when (step) {
            is InvolutionStep.Return -> {
                ledger.move(step.from, step.to, step.itemKey, step.quantity, txn)
                listOf(Flow(step.itemKey, step.quantity, step.from, step.to, FlowKind.MOVE))
            }

            is InvolutionStep.Retract -> {
                ledger.burn(step.from, step.itemKey, step.quantity, SinkKind.ROLLBACK_BURN, txn)
                listOf(Flow(step.itemKey, step.quantity, step.from, HolderId.Sink(SinkKind.ROLLBACK_BURN), FlowKind.BURN))
            }

            is InvolutionStep.Remake -> {
                ledger.craft(step.ingredients, step.product, txn)
                craftFlows(step.ingredients, step.product)
            }
        }

        log.append(Transaction(txn, nextSeq(), System.currentTimeMillis(), CauseKind.INVOLUTION, causedBy = null, flows))
    }
}
