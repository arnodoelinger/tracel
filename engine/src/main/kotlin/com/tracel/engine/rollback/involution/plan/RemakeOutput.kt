package com.tracel.engine.rollback.involution.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId

/** A piece of a remade craft's output: lot [lotId], [quantity] of it, put back at [holder]. */
public data class RemakeOutput(
    /** Lot ID. */
    public val lotId: LotId,

    /** Quantity of a lot. */
    public val quantity: Quantity,

    /** Holder. */
    public val holder: HolderId,
)
