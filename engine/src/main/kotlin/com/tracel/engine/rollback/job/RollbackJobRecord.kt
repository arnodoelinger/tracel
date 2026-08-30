package com.tracel.engine.rollback.job

import com.tracel.engine.journal.Journal
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.RollbackJobId

/**
 * What a rollback job actually did — durable enough to look up later, which
 * [Journal] alone never was: it only ever recorded which step
 * indices finished, never the plan itself. Without this, undoing a completed job has
 * nothing to reverse.
 */
public data class RollbackJobRecord(
    public val id: RollbackJobId,
    public val plan: RollbackPlan,
    public val target: RollbackTarget,
    public val create: List<StructureStep> = emptyList(),
    public val destroy: List<StructureStep> = emptyList(),
)
