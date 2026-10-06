package com.tracel.engine.ledger.craft

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/** One ingredient a craft consumes. */
public data class Ingredient(public val holder: HolderId, public val itemKey: ItemKey, public val quantity: Quantity)
