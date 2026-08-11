package com.tracel.engine.ownership

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/**
 * Proof that [job] currently holds the exclusive right to touch every lot in [lotIds].
 *
 * The constructor is private on purpose, and [LotLeaseRegistry.acquire] is `final` — see
 * there for why: this class existing at all is the guarantee. Code that does not have one
 * cannot call [com.tracel.engine.journal.JournalExecutor.execute] or
 * [com.tracel.engine.rollback.InvolutionExecutor.apply] — the compiler refuses, not a runtime
 * check that a future change could accidentally skip.
 */
public class LotLease private constructor(
    public val job: RollbackJobId,
    public val lotIds: Set<LotId>,
) {
    internal companion object {
        /** The one and only place a [LotLease] is minted — called by [LotLeaseRegistry.acquire] alone. */
        internal fun mint(job: RollbackJobId, lotIds: Set<LotId>): LotLease = LotLease(job, lotIds)
    }
}
