package com.tracel.engine.rollback.job

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.RollbackJobId

/**
 * Durable record of a completed or resumable rollback job.
 *
 * Stores the plan and target needed to execute or undo the job later.
 */
public data class RollbackJobRecord(
    /** Rollback job ID. */
    public val id: RollbackJobId,

    /** Rollback plan. */
    public val plan: RollbackPlan,

    /** Rollback target. */
    public val target: RollbackTarget,

    /** Structure changes created by the rollback. */
    public val create: List<StructureStep> = emptyList(),

    /** Structure changes destroyed by the rollback. */
    public val destroy: List<StructureStep> = emptyList(),

    /**
     * The time this rollback restores the world to.
     *
     * Used to reconstruct historical container state when the rollback is undone.
     */
    public val targetTimeMillis: Long? = null,

    /**
     * When this rollback was executed.
     *
     * An undo uses this time to restore the state that existed before the rollback.
     */
    public val executedAtMillis: Long = 0L,
)
