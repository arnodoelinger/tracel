package com.tracel.engine.rollback.lease.acquisition

import com.tracel.engine.rollback.lease.Lease
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId

/** What asking for a lease answered. */
public sealed interface LeaseAcquisition {
    /** Granted lease. */
    public data class Granted(public val lease: Lease) : LeaseAcquisition

    /** Denied lease. */
    public data class Denied(public val conflicts: Map<LotId, RollbackJobId>) : LeaseAcquisition
}
