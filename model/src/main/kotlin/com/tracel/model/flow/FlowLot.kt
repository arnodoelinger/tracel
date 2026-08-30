package com.tracel.model.flow

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity

/**
 * Which lot one flow of a transaction actually moved, and how much of it.
 *
 * A [Flow] names an item key and a quantity, which is everything you need to replay what
 * happened and nothing you need to undo it: ten diamonds moved, but *which* ten decides whose
 * inventory a rollback has to reach into. Without this link there is no way to get from "this
 * transaction" to "these lots", and a rollback driven by a filter — a player, a time window, a
 * radius — has no roots to start from.
 *
 * [flowIndex] points into the transaction's own [Flow] list, so the source holder a portion has
 * to go back to is the one that flow already names.
 */
public data class FlowLot(
    public val flowIndex: Int,
    public val lotId: LotId,
    public val quantity: Quantity,
)
