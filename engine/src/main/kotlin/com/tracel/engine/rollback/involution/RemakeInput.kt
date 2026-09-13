package com.tracel.engine.rollback.involution

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

public data class RemakeInput(
    /** Lot ID. */
    public val lotId: LotId,

    /** Item key. */
    public val itemKey: ItemKey,

    /** Quantity of a lot. */
    public val quantity: Quantity,
)
