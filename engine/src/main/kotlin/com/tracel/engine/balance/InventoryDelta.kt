package com.tracel.engine.balance

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * Capture saw [holder]'s stock of [itemKey] change by [delta] (gained / lost).
 *
 * [fromGap] means the baseline was rebuilt from the ledger and (!) not observed.
 */
public data class InventoryDelta(
    public val holder: HolderId,
    public val itemKey: ItemKey,
    public val delta: Long,
    public val fromGap: Boolean = false,
)
