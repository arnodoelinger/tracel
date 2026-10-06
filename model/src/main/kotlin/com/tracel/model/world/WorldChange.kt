package com.tracel.model.world

import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq

/**
 * One entry in the world-change log: something in the world stopped looking one way and started
 * looking another.
 */
public data class WorldChange(
    public val seq: Seq,
    public val action: ActionKind,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val epochMillis: Long,
    public val at: BlockPos,
    public val subject: ChangeSubject,
)
