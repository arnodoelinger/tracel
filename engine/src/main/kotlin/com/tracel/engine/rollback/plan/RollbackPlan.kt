package com.tracel.engine.rollback.plan

import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId

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
    /** How many steps replace material that is gone: [RollbackStep.Mint]s and [RollbackStep.Debt]s. */
    public val mintCount: Int get() = steps.count { it is RollbackStep.Mint || it is RollbackStep.Debt }

    /** How many crafts are unmade. */
    public val unmakeCount: Int get() = steps.count { it is RollbackStep.Unmake }

    /** How many lots are taken, a [RollbackStep.TakeRun] counting for each lot in it. */
    public val takeCount: Int
        get() = steps.sumOf { step ->
            when (step) {
                is RollbackStep.Take -> 1
                is RollbackStep.TakeRun -> step.size
                else -> 0
            }
        }

    /** Every holder the plan takes from or hands to, but not those that material is minted for. */
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

    /** Every lot the plan names, which is exactly what it has to lease. */
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
