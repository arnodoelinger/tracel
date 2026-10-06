package com.tracel.engine.rollback.lease

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** The lots a job has to itself while it runs. */
public data class Lease(
    public val job: RollbackJobId,
    public val lotIds: Set<LotId>,
)
