package com.tracel.engine.rollback.plan.step

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity

/** [quantity] of lot [lotId] that went into a craft. */
public data class LotContribution(
    /** Lot ID. */
    public val lotId: LotId,

    /** Quantity of a lot. */
    public val quantity: Quantity
)
