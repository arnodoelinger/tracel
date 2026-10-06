package com.tracel.engine.ledger.craft

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/** What a craft produces. Exactly one product per craft. */
public data class Product(public val holder: HolderId, public val itemKey: ItemKey, public val quantity: Quantity)
