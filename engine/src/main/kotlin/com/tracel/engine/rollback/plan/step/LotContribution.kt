package com.tracel.engine.rollback.plan.step

import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId

/** [quantity] of lot [lotId] that went into a craft. */
public data class LotContribution(
    /** Lot ID. */
    public val lotId: LotId,

    /** Quantity of a lot. */
    public val quantity: Quantity
)
