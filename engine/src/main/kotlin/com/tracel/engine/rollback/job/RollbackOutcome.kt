package com.tracel.engine.rollback.job

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** How [RollbackJobCoordinator.run] ended. */
public sealed interface RollbackOutcome {
    /** The plan was applied and its lots are leased no more. */
    public data class Applied(public val plan: RollbackPlan) : RollbackOutcome

    /** Some lots are leased to other jobs; [conflicts] says which, and to whom. Nothing was done. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : RollbackOutcome

    /** The ledger moved between planning and leasing; [replan] is the plan that holds now. Nothing was done. */
    public data class Stale(public val replan: RollbackPlan) : RollbackOutcome
}
