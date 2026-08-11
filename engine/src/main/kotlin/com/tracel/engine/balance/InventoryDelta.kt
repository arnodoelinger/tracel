package com.tracel.engine.balance

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * A raw observation from capture: [holder]'s stock of [itemKey] changed by
 * [delta] (positive = gained, negative = lost). [TransactionBalancer] turns a
 * set of these into balanced flows.
 */
public data class InventoryDelta(public val holder: HolderId, public val itemKey: ItemKey, public val delta: Long)
