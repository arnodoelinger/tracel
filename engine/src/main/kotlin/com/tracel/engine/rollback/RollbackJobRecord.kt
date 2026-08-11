package com.tracel.engine.rollback

import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId

/**
 * What a rollback job actually did — durable enough to look up later, which
 * [com.tracel.engine.journal.Journal] alone never was: it only ever recorded which step
 * indices finished, never the plan itself. Without this, undoing a completed job has
 * nothing to reverse.
 */
public data class RollbackJobRecord(
    public val id: RollbackJobId,
    public val plan: RollbackPlan,
    public val restoreTo: HolderId,
)
