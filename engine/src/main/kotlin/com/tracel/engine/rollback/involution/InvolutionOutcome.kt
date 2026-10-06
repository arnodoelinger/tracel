package com.tracel.engine.rollback.involution

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.engine.rollback.involution.plan.InvolutionStep

/** How [InvolutionJobCoordinator.undo] ended. */
public sealed interface InvolutionOutcome {
    /** No job by that id is on an undo stack. */
    public data object NotFound : InvolutionOutcome

    /** Some lots are leased to other jobs; [conflicts] says which, and to whom. Nothing was undone. */
    public data class Blocked(public val conflicts: Map<LotId, RollbackJobId>) : InvolutionOutcome

    /**
     * Ledger undo finished (or resumed).
     *
     * Physical restore has not run for this job.
     */
    public data class Undone(public val steps: List<InvolutionStep>) : InvolutionOutcome

    /** The journal says every step ran already, so nothing was done; [steps] is what the undo consisted of. */
    public data class AlreadyUndone(public val steps: List<InvolutionStep>) : InvolutionOutcome
}
