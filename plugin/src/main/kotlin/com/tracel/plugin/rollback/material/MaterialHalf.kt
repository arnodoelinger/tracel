package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.trace.RollbackTrace
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/** Transaction-log half as composition sees it: make the world agree with what the ledger already did. */
interface MaterialHalf {
    /** Per-holder deltas, once, before the ledger runs. After unmake the output lot is gone. */
    suspend fun deltasFor(plan: RollbackPlan, target: RollbackTarget): Map<HolderId, Map<ItemKey, Long>>

    /** The same for undo, which needs no ledger read at all. */
    fun deltasForUndo(steps: List<InvolutionStep>, noise: Set<LotId>): Map<HolderId, Map<ItemKey, Long>>

    /** Abort only if a world is unloaded. Everything else is per-holder. */
    fun preflight(deltas: Map<HolderId, Map<ItemKey, Long>>): PreflightResult

    /** One global census of uuid destinations. Skips hulls structure is [respawning] right now. */
    suspend fun locate(deltas: Map<HolderId, Map<ItemKey, Long>>, respawning: Set<UUID> = emptySet()): EntityCensus

    /** After ledger apply. The ledger already committed. */
    suspend fun restore(
        deltas: Map<HolderId, Map<ItemKey, Long>>,
        job: RollbackJobId,
        knownGone: Set<HolderId>? = null,
        respawnAt: Map<HolderId.ItemEntity, HolderId> = emptyMap(),
        trace: RollbackTrace = RollbackTrace.NONE,
        census: EntityCensus = EntityCensus.EMPTY,
        settled: CompletableDeferred<Set<HolderId>>? = null,
        asOf: Long? = null,
    ): RestorationReport

    /** After the ledger undid. [asOf] is the original job's run time, not the historical target. */
    suspend fun undoRestore(steps: List<InvolutionStep>, job: RollbackJobId, noise: Set<LotId>, asOf: Long? = null): RestorationReport

    /** Worn tools the plan reached, back to the damage they had at [asOf]. After [restore]. */
    suspend fun rewear(plan: RollbackPlan, target: RollbackTarget, job: RollbackJobId, asOf: Long)

    /** The same after an undo: back to the damage they had when the job ran, [asOf]. After [undoRestore]. */
    suspend fun rewearUndo(steps: List<InvolutionStep>, asOf: Long)

    /** Spawn returns onto vanished drops, after the origin container is gone. */
    suspend fun respawnReturnedDrops(
        steps: List<InvolutionStep>,
        job: RollbackJobId,
        hullAt: Map<UUID, HolderId> = emptyMap(),
    ): RestorationReport
}
