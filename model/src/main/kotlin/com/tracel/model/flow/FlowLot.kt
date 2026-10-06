package com.tracel.model.flow

import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId

/** Which lot one flow of a transaction actually moved, and how much of it. */
public data class FlowLot(
    public val flowIndex: Int,
    public val lotId: LotId,
    public val quantity: Quantity,
)
