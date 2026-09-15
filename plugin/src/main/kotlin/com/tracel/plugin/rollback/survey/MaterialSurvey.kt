package com.tracel.plugin.rollback.survey

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

/** Everything the material half of a plan needs, worked out on its own thread. */
internal data class MaterialSurvey(
    val plan: RollbackPlan,
    val target: RollbackTarget,
    val roots: List<LotId>,
    val vanished: Set<HolderId>,
    val witness: Long?,
    val placedAndUnreachable: Int = 0,
)

/** Empty survey for a job with no material side. */
internal val NO_MATERIAL = MaterialSurvey(
    RollbackPlan(emptyList()),
    RollbackTarget.PerRoot(emptyMap()),
    emptyList(),
    emptySet(),
    null,
)
