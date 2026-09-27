package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

/** Rollback destination. */
public fun RollbackTarget.destinationFor(plan: RollbackPlan, lotId: LotId): HolderId = when (this) {
    is RollbackTarget.Uniform -> holder
    is RollbackTarget.PerRoot -> {
        val root = plan.rootOf[lotId] ?: lotId
        byRoot[root] ?: error("no destination for lot $lotId (root $root) — the plan and the target disagree")
    }
}
