package com.tracel.plugin.rollback.material.census

import com.tracel.model.holder.HolderId
import java.util.*

/** Entity-named holders. */
class EntityCensus(
    val at: Map<UUID, HolderId.Block>,
    val missing: Set<UUID>,
    val trackerNeeded: Set<UUID>,
) {
    companion object {
        val EMPTY = EntityCensus(emptyMap(), emptySet(), emptySet())
    }
}
