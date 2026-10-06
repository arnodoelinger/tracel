package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.plan.InvolutionStep
import com.tracel.engine.rollback.involution.plan.physicalDeltasForUndo
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.physicalDeltas
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotId

/** Computes per-holder item deltas from [plan], reading the ledger once before it runs. */
internal suspend fun MaterialRestorer.planDeltas(
    plan: RollbackPlan,
    target: RollbackTarget
): Map<HolderId, Map<ItemKey, Long>> =
    physicalDeltas(plan, target, services.ledger)

/** Undo deltas. */
internal fun MaterialRestorer.undoDeltas(
    steps: List<InvolutionStep>,
    noise: Set<LotId>
): Map<HolderId, Map<ItemKey, Long>> =
    physicalDeltasForUndo(steps, noise)
