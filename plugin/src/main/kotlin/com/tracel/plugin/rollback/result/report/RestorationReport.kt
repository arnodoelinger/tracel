package com.tracel.plugin.rollback.result.report

import com.tracel.model.holder.HolderId

/**
 * Restoration report after ledger + physical.
 *
 * [queued] is offline delivery, it's not a failure.
 */
data class RestorationReport(
    val failures: Map<HolderId, String>,
    val queued: Map<HolderId, String> = emptyMap(),
    val spilled: Int = 0,
) {
    val fullyRestored: Boolean get() = failures.isEmpty()
}
