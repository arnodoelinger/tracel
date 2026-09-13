package com.tracel.engine.rollback.plan

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/** What escrow owes each dest after the job. */
public suspend fun escrowDeliveries(
    plan: RollbackPlan,
    target: RollbackTarget,
    ledger: LotLedger,
): Map<HolderId, Map<ItemKey, Long>> = ledger.atomically {
    val out = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
    for (step in plan.steps) {
        val lotId = when (step) {
            is RollbackStep.Take -> step.lotId
            is RollbackStep.Mint -> step.lotId
            is RollbackStep.Debt -> step.lotId

            is RollbackStep.Unmake -> continue
        }
        val quantity = when (step) {
            is RollbackStep.Take -> step.quantity
            is RollbackStep.Mint -> step.quantity
            is RollbackStep.Debt -> step.quantity
            is RollbackStep.Unmake -> continue
        }
        out.getOrPut(target.destinationFor(plan, lotId)) { mutableMapOf() }
            .merge(ledger.itemKeyOf(lotId), quantity.raw, Long::plus)
    }
    out
}
