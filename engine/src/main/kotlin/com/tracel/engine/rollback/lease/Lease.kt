package com.tracel.engine.rollback.lease

import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId

/** The lots a job has to itself while it runs. */
public data class Lease(
    public val job: RollbackJobId,
    public val lotIds: Set<LotId>,
)
