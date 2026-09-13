package com.tracel.engine.rollback.job

import com.tracel.engine.ownership.LotLease
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** Lease decision, before any world write. */
public sealed interface Reservation {
    /** Lots held until [RollbackJobCoordinator.apply] releases them. */
    public data class Granted(
        public val job: RollbackJobId,
        public val lease: LotLease,
        public val plan: RollbackPlan,
    ) : Reservation

    /** Lease conflict. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : Reservation

    /**
     * Journal moved between plan and lease;
     *
     * [replan] is current.
     */
    public data class Stale(public val replan: RollbackPlan) : Reservation
}
