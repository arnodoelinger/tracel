package com.tracel.engine.rollback

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ownership.LotLease
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.TxnId

/**
 * Physically applies one [InvolutionStep] against the ledger.
 *
 * Takes a [LotLease] for the same reason [com.tracel.engine.journal.JournalExecutor] does:
 * undoing a job touches exactly the lots the original rollback touched, and nothing else may
 * be mutating them concurrently while that happens.
 */
public class InvolutionExecutor(private val ledger: LotLedger) {
    public fun apply(lease: LotLease, step: InvolutionStep, txn: TxnId) {
        when (step) {
            is InvolutionStep.Return ->
                ledger.move(step.from, step.to, step.itemKey, step.quantity, txn)

            is InvolutionStep.Retract ->
                ledger.burn(step.from, step.itemKey, step.quantity, SinkKind.ROLLBACK_BURN, txn)

            is InvolutionStep.Remake ->
                ledger.craft(step.ingredients, step.product, txn)
        }
    }
}
