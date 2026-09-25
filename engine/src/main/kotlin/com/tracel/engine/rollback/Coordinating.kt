package com.tracel.engine.rollback

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.job.Reservation
import com.tracel.engine.rollback.job.RollbackOutcome
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** Plan, lease, verify, apply. */
public interface RollbackCoordinating {
    /** Run a rollback job. */
    public suspend fun run(
        job: RollbackJobId,
        rootLots: List<LotId>,
        target: RollbackTarget,
        vanished: Set<HolderId>,
        crashPoint: CrashPoint,
        recordsOwnJob: Boolean,
        prepared: RollbackPlan?,
        preparedAt: Long?,
        structural: Boolean,
        covered: Set<HolderId>?,
    ): RollbackOutcome

    /** Reserve rollback job. */
    public suspend fun reserve(
        job: RollbackJobId,
        rootLots: List<LotId>,
        target: RollbackTarget,
        vanished: Set<HolderId>,
        prepared: RollbackPlan?,
        preparedAt: Long?,
        structural: Boolean,
        covered: Set<HolderId>?,
    ): Reservation

    /** Apply rollback. */
    public suspend fun apply(
        reservation: Reservation.Granted,
        target: RollbackTarget,
        crashPoint: CrashPoint,
        recordsOwnJob: Boolean,
    ): RollbackOutcome
}

/** Undo an applied job. */
public interface InvolutionCoordinating {
    public suspend fun undo(
        job: RollbackJobId,
        crashPoint: CrashPoint,
    ): InvolutionOutcome
}
