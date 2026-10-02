package com.tracel.plugin.rollback.survey

import com.tracel.model.holder.HolderId

/** All planning needs from the live world: which uuid holders are already gone. */
interface WorldCensus {
    /** One global pass. The planner uses it so vanished lots are compensated, not taken. */
    suspend fun vanishedEntities(holders: Set<HolderId>): Set<HolderId>
}
