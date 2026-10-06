package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId

/**
 * Where what a rollback reclaims from [lotId] is delivered under this target: the one holder of a uniform target, or
 * the holder that was recorded for the root [lotId] came from. Throws if a per-root target has no holder for it,
 * because the plan and the target then disagree.
 */
public fun RollbackTarget.destinationFor(plan: RollbackPlan, lotId: LotId): HolderId = when (this) {
    is RollbackTarget.Uniform -> holder
    is RollbackTarget.PerRoot -> {
        val root = plan.rootOf[lotId] ?: lotId
        byRoot[root] ?: error("no destination for lot $lotId (root $root) — the plan and the target disagree")
    }
}
