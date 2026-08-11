package com.tracel.engine.rollback

/**
 * The ordered steps a rollback needs to run. [RollbackStep.Unmake] steps
 * always come before the [RollbackStep.Take]s that depend on their output
 * existing again.
 */
public data class RollbackPlan(public val steps: List<RollbackStep>) {
    public val mintCount: Int get() = steps.count { it is RollbackStep.Mint || it is RollbackStep.Debt }
    public val takeCount: Int get() = steps.count { it is RollbackStep.Take }
    public val unmakeCount: Int get() = steps.count { it is RollbackStep.Unmake }
}
