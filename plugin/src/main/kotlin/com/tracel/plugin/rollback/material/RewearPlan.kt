package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.plan.InvolutionStep
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.adapter.rollback.material.rewear

internal data class Rewear(
    val lotId: LotId,
    val itemKey: ItemKey,
    val holder: HolderId,
    val current: Int?,
    val target: Int
)

internal data class WearEnd(val history: LotId, val lot: LotId, val holder: HolderId, val fresh: Boolean)

/** Every worn tool a rollback reached, back to the damage it had at [asOf]. */
internal suspend fun MaterialRestorer.rewearPlan(
    plan: RollbackPlan,
    target: RollbackTarget,
    job: RollbackJobId,
    asOf: Long,
    only: Set<HolderId>? = null,
) {
    val ends = LinkedHashMap<LotId, WearEnd>()
    val leaving = ArrayList<LotId>()
    services.atomically {
        for (step in plan.steps) {
            when (step) {
                is RollbackStep.Take -> ends[step.lotId] =
                    WearEnd(
                        step.lotId,
                        step.lotId,
                        target.destinationFor(plan, step.lotId),
                        fresh = ends[step.lotId]?.fresh == true
                    )

                is RollbackStep.TakeRun -> for (k in 0 until step.size) {
                    val lot = step.lotAt(k)
                    ends[lot] = WearEnd(lot, lot, target.destinationFor(plan, lot), fresh = ends[lot]?.fresh == true)
                }

                is RollbackStep.Mint -> services.ledger.compensationOf(step.lotId, job)?.let {
                    ends[it] = WearEnd(step.lotId, it, target.destinationFor(plan, step.lotId), fresh = true)
                }

                is RollbackStep.Debt -> services.ledger.compensationOf(step.lotId, job)?.let {
                    ends[it] = WearEnd(step.lotId, it, target.destinationFor(plan, step.lotId), fresh = true)
                }

                is RollbackStep.Unmake -> {
                    for ((lotId) in step.inputs) ends[lotId] = WearEnd(lotId, lotId, step.holder, fresh = true)
                    for ((lotId) in step.outputs) leaving += lotId
                }
            }
        }
    }
    val wanted = if (only == null) ends.values.toList() else ends.values.filter { it.holder in only }
    rewear(wanted, if (only == null) leaving else emptyList(), asOf)
}

/** The same after an undo: back to the damage each tool had when the job ran, [asOf]. */
internal suspend fun MaterialRestorer.rewearSteps(steps: List<InvolutionStep>, asOf: Long) {
    val ends = ArrayList<WearEnd>()
    val leaving = ArrayList<LotId>()
    for (step in steps) {
        when (step) {
            is InvolutionStep.Return -> ends += WearEnd(step.lotId, step.lotId, step.to, fresh = false)
            is InvolutionStep.Remake -> {
                for ((lotId, _, holder) in step.outputs) ends += WearEnd(lotId, lotId, holder, fresh = true)
                for ((lotId) in step.inputs) leaving += lotId
            }

            is InvolutionStep.Retract -> Unit
        }
    }
    rewear(ends, leaving, asOf)
}
