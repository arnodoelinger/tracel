package com.tracel.engine.rollback

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey

/**
 * Physically applies one [RollbackStep] against the ledger.
 *
 * Every reclaimed unit lands in the job's own `HolderId.Escrow` first —
 * see [release] for why that one choice is what makes the whole rollback
 * crash-safe.
 *
 * [RollbackStep.Debt] mints a replacement the same way [RollbackStep.Mint]
 * does.
 */
public class RollbackExecutor(private val ledger: LotLedger) {
    public fun apply(job: RollbackJobId, step: RollbackStep, txn: TxnId) {
        val escrow = HolderId.Escrow(job)
        when (step) {
            is RollbackStep.Take ->
                ledger.deposit(escrow, listOf(ledger.withdrawExact(step.holder, step.lotId)))

            is RollbackStep.Mint ->
                ledger.compensate(escrow, step.lotId, step.quantity, txn, job)

            is RollbackStep.Debt ->
                ledger.compensate(escrow, step.lotId, step.quantity, txn, job)

            is RollbackStep.Unmake -> {
                ledger.destroy(step.holder, step.outputLot)
                for (input in step.inputs) ledger.restore(step.holder, input.lotId, input.quantity)
            }
        }
    }

    /** Moves everything the job collected in escrow to its final destination — the barrier between take and restore. */
    public fun release(job: RollbackJobId, restoreTo: HolderId, itemKeys: Set<ItemKey>, txn: TxnId) {
        val escrow = HolderId.Escrow(job)
        for (itemKey in itemKeys) {
            val total = ledger.totalAt(escrow, itemKey) ?: continue
            ledger.deposit(restoreTo, ledger.withdraw(escrow, itemKey, total, txn))
        }
    }

    /** Every item key a [release] for this plan will need to look for in escrow. */
    public fun escrowItemKeys(plan: RollbackPlan): Set<ItemKey> =
        plan.steps.filterIsInstance<RollbackStep.Take>().mapTo(mutableSetOf()) { ledger.itemKeyOf(it.lotId) } +
            plan.steps.filterIsInstance<RollbackStep.Mint>().mapTo(mutableSetOf()) { ledger.itemKeyOf(it.lotId) } +
            plan.steps.filterIsInstance<RollbackStep.Debt>().mapTo(mutableSetOf()) { ledger.itemKeyOf(it.lotId) }
}
