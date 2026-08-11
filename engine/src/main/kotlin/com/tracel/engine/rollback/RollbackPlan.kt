package com.tracel.engine.rollback

import com.tracel.model.id.LotId

/**
 * The ordered steps a rollback needs to run. [RollbackStep.Unmake] steps
 * always come before the [RollbackStep.Take]s that depend on their output
 * existing again.
 */
public data class RollbackPlan(public val steps: List<RollbackStep>) {
    public val mintCount: Int get() = steps.count { it is RollbackStep.Mint || it is RollbackStep.Debt }
    public val takeCount: Int get() = steps.count { it is RollbackStep.Take }
    public val unmakeCount: Int get() = steps.count { it is RollbackStep.Unmake }

    /**
     * Every lot this plan touches — what a [com.tracel.engine.ownership.LotLease] over this
     * plan needs to reserve before [JournalExecutor] is allowed to apply it. An [RollbackStep.Unmake]
     * touches both its output and every one of its inputs, not just the traced lot.
     */
    public val touchedLots: Set<LotId>
        get() = buildSet {
            for (step in steps) when (step) {
                is RollbackStep.Take -> add(step.lotId)
                is RollbackStep.Mint -> add(step.lotId)
                is RollbackStep.Debt -> add(step.lotId)
                is RollbackStep.Unmake -> {
                    add(step.outputLot)
                    step.inputs.forEach { add(it.lotId) }
                }
            }
        }
}
