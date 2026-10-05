package com.tracel.model.event

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.world.BlockPos

/** What somebody did that changed neither the world nor who holds what. */
public enum class EventKind {
    CHAT,
    COMMAND,
    JOIN,
    QUIT,
    DEATH,
    SHOOT,
    HIT,
}

/**
 * One entry in the event log: something said, typed, or a session starting or ending.
 *
 * Nothing here can be rolled back, so nothing here is ever read by a rollback.
 *
 * @property at where [by] stood, when that is known
 * @property text what was said or typed; `null` for a session
 */
public data class ActorEvent(
    public val seq: Seq,
    public val kind: EventKind,
    public val by: HolderId?,
    public val epochMillis: Long,
    public val at: BlockPos?,
    public val text: String?,
)
