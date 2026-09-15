package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.outcome.Unreachable

/**
 * Worlds named by [steps] must be loaded (not on the global region; UUID lookup is already
 * done off-thread); hopping there cost a tick for a map read.
 */
internal fun StructureRestorer.preflightWorlds(steps: List<StructureStep>): PreflightResult {
    val missing = steps.asSequence().map { it.at.world }.distinct()
        .firstOrNull { worldOf(it) == null }
    return if (missing == null) {
        PreflightResult.Ok
    } else {
        Unreachable(HolderId.Block(missing, 0, 0, 0), "world is not loaded")
    }
}
