package com.tracel.engine.rollback.plan

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity

public data class LotContribution(
    /** Lot ID. */
    public val lotId: LotId,

    /** Quantity of a lot. */
    public val quantity: Quantity
)
