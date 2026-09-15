package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.plugin.util.ownsChunkAt
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicIntegerArray

/** Apply [steps]. Grouped by chunk, all dispatched at once. */
internal suspend fun StructureRestorer.restoreSteps(
    steps: List<StructureStep>,
    force: Boolean,
    trace: RollbackTrace, // TODO: remove me
    structurePhase: StructurePhase, // TODO: remove me
    drain: Boolean,
    keepCargoFor: Set<UUID>,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
    dumpHeldCargo: Boolean,
): StructureReport {
    if (steps.isEmpty()) return StructureReport.EMPTY

    for (step in steps) {
        if (step is StructureStep.SetBlock) {
            BlockDataCache.of(step.target.data)
            BlockDataCache.of(step.expected.data)
        }
    }

    val groups = steps.groupBy { dispatchAt(it).regionKey() }.values.toList()

    // Not a lock
    val claimed = AtomicIntegerArray(groups.size)

    val reports = coroutineScope {
        val dispatched = trace.stopwatch("${structurePhase.traceName} / hop")
        groups.indices.map { index ->
            async {
                val anchor = dispatchAt(groups[index].first())
                withContext(services.schedulers.region(HolderId.Block(anchor.world, anchor.x, anchor.y, anchor.z))) {
                    dispatched()
                    val world = worldOf(anchor.world)
                    if (world == null) {
                        StructureReport(emptyList(), groups[index].map { SkippedStep(it.at, "world is not loaded") })
                    } else {
                        services.selfManagedWorld.whileRestoring {
                        applyGroup(world, claim(index, groups, claimed), force, trace, structurePhase, drain, keepCargoFor, ledgerCargoFor, ledgerHeldBy, dumpHeldCargo)
                    }
                    }
                }
            }
        }.awaitAll()
    }

    return StructureReport(
        reports.flatMap { it.applied },
        reports.flatMap { it.skipped },
        reports.sumOf { it.overwritten },
    )
}

/** Despawn a sailed boat on its current chunk. */
internal fun StructureRestorer.dispatchAt(step: StructureStep): BlockPos {
    if (step is StructureStep.RemoveEntity) {
        val here = services.whereabouts.at(step.entity) ?: return step.at
        return BlockPos(here.world, here.x, here.y, here.z)
    }
    return step.at
}

/** Claim every group this `Folia` region thread owns. */
internal fun StructureRestorer.claim(
    index: Int,
    groups: List<List<StructureStep>>,
    claimed: AtomicIntegerArray,
): List<StructureStep> = claimOwned(index, groups, claimed, ::ownsChunkAt)
