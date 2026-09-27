package com.tracel.plugin.rollback.result.outcome

import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.structure.CompositeRollbackPlan
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.plugin.rollback.trace.RollbackTrace

/** Planned, not yet applied rollback. */
data class Planned(
    val composite: CompositeRollbackPlan,
    val target: RollbackTarget,
    val roots: List<LotId>,
    val vanished: Set<HolderId> = emptySet(),
    val witness: Long? = null,
    val flushed: Boolean = true,
    val placedAndUnreachable: Int = 0,
    val trace: RollbackTrace = RollbackTrace.NONE,
    val targetTimeMillis: Long? = null,
    val structural: Boolean = true,
    val covered: Set<HolderId>? = null,
    val by: HolderId? = null,
)
