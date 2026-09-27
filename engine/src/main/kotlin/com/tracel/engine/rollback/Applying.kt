package com.tracel.engine.rollback

import com.tracel.engine.ownership.LotLease
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/** Apply planned rollback ledger steps. */
public interface RollbackApplying {
    /** Prefetch, apply every step, append flows in [flowLimit]-sized txns. */
    public suspend fun applyAll(
        job: RollbackJobId,
        steps: List<RollbackStep>,
        txn: TxnId,
        flowLimit: Int,
        nextTxn: suspend () -> TxnId,
        plan: RollbackPlan?,
        target: RollbackTarget?,
    )

    /** Apply one step, one txn. Take parks in escrow until [release]. */
    public suspend fun apply(job: RollbackJobId, step: RollbackStep, txn: TxnId)

    /** Drain escrow to destinations. */
    public suspend fun release(job: RollbackJobId, plan: RollbackPlan, target: RollbackTarget, txn: TxnId)
}

/** Apply planned undo ledger steps. */
public interface InvolutionApplying {
    /** Dry-run in order so later Returns can spend earlier ones. Fail here, not mid-apply. */
    public suspend fun checkSatisfiable(lease: LotLease, steps: List<InvolutionStep>)

    /** One undo step under the same lease the original job held. */
    public suspend fun apply(lease: LotLease, step: InvolutionStep, txn: TxnId)
}
