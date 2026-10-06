package com.tracel.model.flow

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity

/** One line of a transaction. */
public data class Flow(
    public val itemKey: ItemKey,
    public val quantity: Quantity,
    public val source: HolderId,
    public val destination: HolderId,
    public val kind: FlowKind,
)
