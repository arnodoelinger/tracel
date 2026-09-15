package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.util.worldId

/**
 * Preflight worlds.
 *
 * Abort only if the world is unloaded. Vanished drops and "not a container yet" are per-holder
 * (apply takes nothing / retries).
 */
internal fun MaterialRestorer.preflightWorlds(deltas: Map<HolderId, Map<ItemKey, Long>>): PreflightResult {
    val missing = deltas.keys.asSequence().mapNotNull(HolderId::worldId).distinct()
        .firstOrNull { worldOf(it) == null }
    return if (missing == null) {
        PreflightResult.Ok
    } else {
        Unreachable(HolderId.Block(missing, 0, 0, 0), "world is not loaded")
    }
}
