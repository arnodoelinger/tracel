package com.tracel.plugin.rollback.result.report

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * Restoration report after ledger and physical.
 *
 * @param failures the failures encountered during the restoration process
 * @param queued the queued items that are offline deliveries
 * @param spilled the spilled items
 * @param shortfall is what could not be taken and found no give to withhold it from
 */
data class RestorationReport(
    val failures: Map<HolderId, String>,
    val queued: Map<HolderId, String> = emptyMap(),
    val spilled: Int = 0,
    val shortfall: Map<ItemKey, Long> = emptyMap(),
) {
    val fullyRestored: Boolean get() = failures.isEmpty()
}
