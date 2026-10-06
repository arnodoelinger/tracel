package com.tracel.engine.ledger

import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId

/** A quantity taken from one specific lot by a withdrawal — which batch, how much. */
public data class LotPortion(public val lotId: LotId, public val quantity: Quantity)
