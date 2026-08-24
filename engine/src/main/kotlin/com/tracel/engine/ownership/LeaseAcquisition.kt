package com.tracel.engine.ownership

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** The result of [LotLeaseRegistry.acquire]. */
public sealed interface LeaseAcquisition {
    public data class Granted(public val lease: LotLease) : LeaseAcquisition
    public data class Denied(public val conflicts: Map<LotId, RollbackJobId>) : LeaseAcquisition
}
