package com.tracel.engine.rollback.job

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** Result of [RollbackJobCoordinator.run]. */
public sealed interface RollbackOutcome {
    /** Lease already released. */
    public data class Applied(public val plan: RollbackPlan) : RollbackOutcome

    /** Lease conflict. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : RollbackOutcome

    /** Journal moved between plan and lease; [replan] is current. */
    public data class Stale(public val replan: RollbackPlan) : RollbackOutcome
}
