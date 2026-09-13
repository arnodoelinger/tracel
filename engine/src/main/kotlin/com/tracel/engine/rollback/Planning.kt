package com.tracel.engine.rollback

import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.world.WorldChange

/** Ledger steps for a rollback. */
public interface RollbackPlanning {
    public val placedAndUnreachable: Int

    /** Rollback plan. */
    public suspend fun plan(rootLots: List<LotId>): RollbackPlan
}

/** Structural create / destroy halves. */
public interface StructurePlanning {
    public fun plan(changes: List<WorldChange>): Pair<List<StructureStep>, List<StructureStep>>
}

/** Ledger steps that reverse an applied job. */
public interface InvolutionPlanning {
    public suspend fun plan(job: RollbackJobRecord, vanished: Set<HolderId>): List<InvolutionStep>
}
