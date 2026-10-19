package com.tracel.model.event

import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.BlockPos
import java.util.UUID

/**
 * One entry in the event log: something said, typed, or a session starting or ending.
 *
 * Nothing here can be rolled back, so nothing here is ever read by a rollback.
 */
public data class ActorEvent(
    public val seq: Seq,
    public val kind: EventKind,
    public val by: HolderId?,
    public val epochMillis: Long,
    public val at: BlockPos?,
    public val text: String?,
    public val entity: UUID? = null,
)
