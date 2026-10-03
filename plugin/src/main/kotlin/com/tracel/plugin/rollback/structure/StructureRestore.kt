package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.block.UNSUPPORTED
import com.tracel.plugin.util.ownsChunkAt
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.logging.Level
import kotlin.coroutines.cancellation.CancellationException

/** How many times steps that changed regions mid-restore are sent on before they are reported as skipped. */
private const val MAX_REDISPATCH = 3

/** Apply [steps]. Grouped by chunk, all dispatched at once. */
internal suspend fun StructureRestorer.restoreSteps(
    steps: List<StructureStep>,
    force: Boolean,
    keepCargoFor: Set<UUID>,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
    dumpHeldCargo: Boolean,
    driftOnly: Boolean = false,
): StructureReport {
    if (steps.isEmpty()) return StructureReport.EMPTY

    for (step in steps) {
        if (step is StructureStep.SetBlock) {
            BlockDataCache.of(step.target.data)
            BlockDataCache.of(step.expected.data)
        }
    }

    suspend fun dispatch(steps: List<StructureStep>, round: Int = 0): List<StructureReport> {
        val groups = steps.groupBy { dispatchAt(it).regionKey() }.values.toList()

        // Not a lock
        val claimed = AtomicIntegerArray(groups.size)

        // Steps whose chunk changed regions while a slow group waited for its next tick
        val deferred = ConcurrentLinkedQueue<StructureStep>()

        val reports = coroutineScope {
            groups.indices.map { index ->
                async {
                    val anchor = dispatchAt(groups[index].first())
                    // Loaded off the region thread, all at once, before the hop: synchronously on the region
                    // thread each unloaded chunk stalled its tick, one after another
                    worldOf(anchor.world)?.let { world ->
                        val chunks =
                            groups[index].mapTo(HashSet()) { dispatchAt(it).let { at -> (at.x shr 4) to (at.z shr 4) } }
                        chunks.map { (cx, cz) -> async { runCatching { world.getChunkAtAsync(cx, cz).await() } } }
                            .awaitAll()
                    }
                    withContext(
                        services.schedulers.region(
                            HolderId.Block(
                                anchor.world,
                                anchor.x,
                                anchor.y,
                                anchor.z
                            )
                        )
                    ) {
                        val world = worldOf(anchor.world)
                        if (world == null) {
                            StructureReport(
                                emptyList(),
                                groups[index].map { SkippedStep(it.at, "world is not loaded") })
                        } else {
                            val mine = claim(index, groups, claimed)
                            try {
                                applyGroup(
                                    world,
                                    mine,
                                    force,
                                    keepCargoFor,
                                    ledgerCargoFor,
                                    ledgerHeldBy,
                                    dumpHeldCargo,
                                    driftOnly,
                                    deferred,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                logger.log(
                                    Level.WARNING,
                                    "a structure group failed; its steps are reported as skipped",
                                    failure
                                )
                                val why = "restore failed here: ${failure.message ?: failure::class.java.simpleName}"
                                StructureReport(emptyList(), mine.map { SkippedStep(it.at, why) })
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val again = deferred.toList()
        return when {
            again.isEmpty() -> reports
            round >= MAX_REDISPATCH -> reports + again.map {
                StructureReport(
                    emptyList(),
                    listOf(SkippedStep(it.at, "the region changed hands while this was restoring"))
                )
            }

            else -> reports + dispatch(again, round + 1)
        }
    }

    val first = dispatch(steps)

    val unsupported = first.flatMap { it.skipped }.filter { it.reason == UNSUPPORTED }.mapTo(HashSet()) { it.at }
    val retry =
        if (unsupported.isEmpty()) emptyList() else steps.filter { it is StructureStep.SetBlock && it.at in unsupported }
    val reports = if (retry.isEmpty()) first else {
        first.map { report -> report.copy(skipped = report.skipped.filterNot { it.reason == UNSUPPORTED }) } + dispatch(
            retry
        )
    }

    val applied = reports.flatMap { it.applied }
    rescuePlayers(applied)

    return StructureReport(
        applied,
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
): List<StructureStep> = claimOwned(index, groups, claimed) { ownsChunkAt(dispatchAt(it)) }

/** Claim owned groups without touching the world. */
private fun claimOwned(
    index: Int,
    groups: List<List<StructureStep>>,
    claimed: AtomicIntegerArray,
    owns: (StructureStep) -> Boolean,
): List<StructureStep> {
    val mine = ArrayList<StructureStep>()
    for (other in groups.indices) {
        if (other != index && (claimed.get(other) != 0 || !owns(groups[other].first()))) continue
        if (!claimed.compareAndSet(other, 0, 1)) continue
        mine += groups[other]
    }
    return mine
}
