package com.tracel.model.lot

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey

/**
 * An immutable batch of units, created once by [createdBy] and never edited
 * again. A lot's identity survives it moving between holders or being spent —
 * see [LotEdge] for how its life after creation is recorded.
 */
public data class Lot(
    public val id: LotId,
    public val itemKey: ItemKey,
    public val quantity: Quantity,
    public val createdBy: TxnId,
)
