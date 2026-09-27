package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

/**
 * The ordered steps a rollback needs to run. [RollbackStep.Unmake] steps
 * always come before the [RollbackStep.Take]s that depend on their output
 * existing again.
 */
public data class RollbackPlan(
    public val steps: List<RollbackStep>,
    public val rootOf: Map<LotId, LotId> = emptyMap(),
    public val settled: Set<LotId> = emptySet(),
) {
    public val mintCount: Int get() = steps.count { it is RollbackStep.Mint || it is RollbackStep.Debt }
    public val takeCount: Int
        get() = steps.sumOf { step ->
            when (step) {
                is RollbackStep.Take -> 1
                is RollbackStep.TakeRun -> step.size
                else -> 0
            }
        }
    public val unmakeCount: Int get() = steps.count { it is RollbackStep.Unmake }

    public val holders: Set<HolderId>
        get() = buildSet {
            for (step in steps) when (step) {
                is RollbackStep.Take -> add(step.holder)
                is RollbackStep.TakeRun -> add(step.holder)
                is RollbackStep.Unmake -> {
                    add(step.holder)
                    for ((_, holder) in step.outputs) add(holder)
                }

                is RollbackStep.Mint, is RollbackStep.Debt -> Unit
            }
        }

    public val touchedLots: Set<LotId>
        get() = buildSet {
            for (step in steps) when (step) {
                is RollbackStep.Take -> add(step.lotId)
                is RollbackStep.TakeRun -> for (k in 0 until step.size) add(step.lotAt(k))
                is RollbackStep.Mint -> add(step.lotId)
                is RollbackStep.Debt -> add(step.lotId)
                is RollbackStep.Unmake -> {
                    step.outputs.forEach { add(it.lotId) }
                    step.inputs.forEach { add(it.lotId) }
                }
            }
        }
}
