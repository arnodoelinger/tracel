package com.tracel.model.flow

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/**
 * One line of a transaction: a quantity of one item key moving from [source] to
 * [destination]. The recorded, durable shape of "what happened" — the ledger's
 * append-only log is a sequence of these, never edited after the fact.
 */
public data class Flow(
    public val itemKey: ItemKey,
    public val quantity: Quantity,
    public val source: HolderId,
    public val destination: HolderId,
    public val kind: FlowKind,
)
