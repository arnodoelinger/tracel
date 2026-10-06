package com.tracel.engine.rollback.job

import com.tracel.engine.rollback.lease.Lease
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId

/**
 * The answer to asking for a rollback's lots, given before anything in the world has been touched, so that a refusal
 * costs nothing.
 */
public sealed interface Reservation {
    /**
     * Every lot the [plan] touches is leased to [job] under [lease], and stays so until [RollbackJobCoordinator.apply]
     * runs or [RollbackJobCoordinator.cancel] gives them back.
     */
    public data class Granted(
        public val job: RollbackJobId,
        public val lease: Lease,
        public val plan: RollbackPlan,
    ) : Reservation

    /** Some of the lots are leased to other jobs; [conflicts] says which, and to whom. Nothing is held. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : Reservation

    /**
     * The ledger moved between planning and leasing, in a way that changes what this job would do; the plan no longer
     * holds. Nothing is held.
     *
     * [replan] is the plan that does, as of ledger version [replannedAt]. Handed back in as a [PreparedPlan], the next
     * reserve only checks it again instead of planning a third time.
     */
    public data class Stale(public val replan: RollbackPlan, public val replannedAt: Long? = null) : Reservation
}
