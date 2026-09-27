package com.tracel.engine.rollback.involution

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity

public data class RemakeOutput(
    /** Lot ID. */
    public val lotId: LotId,

    /** Quantity of a lot. */
    public val quantity: Quantity,

    /** Holder. */
    public val holder: HolderId,
)
