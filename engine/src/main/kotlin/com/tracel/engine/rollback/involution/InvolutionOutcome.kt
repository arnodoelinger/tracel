package com.tracel.engine.rollback.involution

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** Result of [InvolutionJobCoordinator.undo]. */
public sealed interface InvolutionOutcome {
    /** Job not found. */
    public data object NotFound : InvolutionOutcome

    /** Job has been blocked. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : InvolutionOutcome

    /**
     * Ledger undo finished (or resumed).
     *
     * Physical restore has not run for this job.
     */
    public data class Undone(public val steps: List<InvolutionStep>) : InvolutionOutcome

    /** Journal already complete. */
    public data class AlreadyUndone(public val steps: List<InvolutionStep>) : InvolutionOutcome
}
