package com.tracel.engine.ownership

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** What asking for a lease answered. */
public sealed interface LeaseAcquisition {
    /** Granted lease. */
    public data class Granted(public val lease: LotLease) : LeaseAcquisition

    /** Denied lease. */
    public data class Denied(public val conflicts: Map<LotId, RollbackJobId>) : LeaseAcquisition
}
