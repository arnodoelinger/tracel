package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.census.findVanished
import com.tracel.plugin.rollback.material.census.locateDestinations
import com.tracel.plugin.rollback.survey.WorldCensus
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.trace.RollbackTrace
import java.util.UUID
import java.util.logging.Logger
import kotlinx.coroutines.CompletableDeferred

/** [MaterialHalf] wired to real Bukkit state — inventories, entity cargo, ground drops. */
class MaterialRestorer(internal val services: TracelServices) : MaterialHalf, WorldCensus {
    internal val logger = Logger.getLogger(MaterialRestorer::class.java.name)

    override suspend fun deltasFor(plan: RollbackPlan, target: RollbackTarget): Map<HolderId, Map<ItemKey, Long>> =
        planDeltas(plan, target)

    override fun deltasForUndo(steps: List<InvolutionStep>): Map<HolderId, Map<ItemKey, Long>> = undoDeltas(steps)

    override fun preflight(deltas: Map<HolderId, Map<ItemKey, Long>>): PreflightResult = preflightWorlds(deltas)

    override suspend fun vanishedEntities(holders: Set<HolderId>, trace: RollbackTrace): Set<HolderId> =
        findVanished(holders, trace)

    override suspend fun locate(deltas: Map<HolderId, Map<ItemKey, Long>>, respawning: Set<UUID>): EntityCensus =
        locateDestinations(deltas, respawning)

    override suspend fun restore(
        deltas: Map<HolderId, Map<ItemKey, Long>>,
        job: RollbackJobId,
        knownGone: Set<HolderId>?,
        respawnAt: Map<HolderId.ItemEntity, HolderId>,
        trace: RollbackTrace,
        census: EntityCensus,
        settled: CompletableDeferred<Unit>?,
        asOf: Long?,
    ): RestorationReport = restoreDeltas(deltas, job, knownGone, respawnAt, trace, census, settled, asOf)

    override suspend fun undoRestore(steps: List<InvolutionStep>, job: RollbackJobId, asOf: Long?): RestorationReport =
        restoreUndo(steps, job, asOf)

    override suspend fun respawnReturnedDrops(
        steps: List<InvolutionStep>,
        job: RollbackJobId,
        hullAt: Map<UUID, HolderId>,
    ): RestorationReport = respawnDrops(steps, job, hullAt)
}
