package com.tracel.plugin.rollback.structure.fluid

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.groupByChunk
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.claim
import com.tracel.plugin.rollback.structure.throttled
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.ownsChunkAt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicIntegerArray

/** Drain and settle over everything a job wrote, once per region, after the last pass. */
internal suspend fun StructureRestorer.settleWritten(written: List<StructureStep>, drain: Boolean): StructureReport {
    val blocks = written.filterIsInstance<StructureStep.SetBlock>()
    if (blocks.isEmpty()) return StructureReport.EMPTY
    if (blocks.none { touchesOrFeeds(it.target) || touchesOrFeeds(it.expected) }) return StructureReport.EMPTY
    val groups: List<List<StructureStep>> = groupByChunk(blocks) { it.at }
    val claimed = AtomicIntegerArray(groups.size)

    val reports = coroutineScope {
        groups.indices.map { index ->
            async {
                val anchor = groups[index].first().at
                withContext(services.schedulers.region(HolderId.Block(anchor.world, anchor.x, anchor.y, anchor.z))) {
                    val world = worldOf(anchor.world) ?: return@withContext StructureReport.EMPTY
                    val mine = claim(index, groups, claimed).filterIsInstance<StructureStep.SetBlock>()
                    if (mine.isEmpty()) return@withContext StructureReport.EMPTY

                    // A loaded chunk this region owns, whether or not a step sits in it: streams cross chunk lines
                    val owned = HashMap<Long, Boolean>()
                    val owns = { x: Int, z: Int ->
                        owned.getOrPut(chunkKey(x, z)) {
                            world.isChunkLoaded(x shr 4, z shr 4) && ownsChunkAt(
                                world,
                                x,
                                z
                            )
                        }
                    }

                    // The settle may stir fluids around what was written; the writing itself was marked as it went
                    services.selfManagedWorld.wrote(
                        mine.filter { touchesFluidCached(it.target) || touchesFluidCached(it.expected) }.map { it.at }
                    )

                    // Every written block is walked once more here, so it takes turns like the writing did
                    services.governor.throttled(
                        world,
                        anchor.x shr 4,
                        anchor.z shr 4,
                        services.selfManagedWorld
                    ) { throttle ->
                        val whole = !drain || drainFlowing(world, mine, owns)
                        settleFluids(world, mine, owns) { throttle.yieldIfSpent { owned.clear() } }
                        if (whole) StructureReport.EMPTY
                        else StructureReport(
                            emptyList(),
                            listOf(SkippedStep(mine.first().at, "the fluid drain hit its $MAX_DRAINED cell limit"))
                        )
                    }
                }
            }
        }.awaitAll()
    }
    return reports.fold(StructureReport.EMPTY, StructureReport::plus)
}
