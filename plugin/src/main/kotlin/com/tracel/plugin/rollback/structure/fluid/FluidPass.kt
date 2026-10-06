package com.tracel.plugin.rollback.structure.fluid

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.space.groupByTile
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.governor.throttled
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.claim
import com.tracel.plugin.util.LongHashSet
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.ownsChunkAt
import com.tracel.plugin.util.packed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicIntegerArray

/** Wakes the fluids in and around everything a job wrote, once per region, after the freeze is off. */
internal suspend fun StructureRestorer.settleWritten(written: List<StructureStep>, asItStood: LookupRegion?) {
    val blocks = written.filterIsInstance<StructureStep.SetBlock>()
    if (blocks.isEmpty()) return
    val footprint = HashMap<WorldId, LongHashSet>()
    for (step in blocks) footprint.getOrPut(step.at.world) { LongHashSet(blocks.size) } += packed(step.at.x, step.at.y, step.at.z)
    val groups: List<List<StructureStep>> = groupByTile(blocks) { it.at }
    val claimed = AtomicIntegerArray(groups.size)

    coroutineScope {
        groups.indices.map { index ->
            async {
                val anchor = groups[index].first().at
                withContext(services.schedulers.region(HolderId.Block(anchor.world, anchor.x, anchor.y, anchor.z))) {
                    val world = worldOf(anchor.world) ?: return@withContext
                    val mine = claim(index, groups, claimed).filterIsInstance<StructureStep.SetBlock>()
                    if (mine.isEmpty()) return@withContext
                    val cells = footprint.getValue(anchor.world)

                    // A loaded chunk this region owns, whether a step sits in it: streams cross chunk lines
                    val owned = HashMap<Long, Boolean>()
                    val owns = { x: Int, z: Int ->
                        owned.getOrPut(chunkKey(x, z)) {
                            world.isChunkLoaded(x shr 4, z shr 4) && ownsChunkAt(world, x, z)
                        }
                    }

                    // A fresh guard: the flag stays down, so whatever these ticks set off is logged
                    services.governor.throttled(world, anchor.x shr 4, anchor.z shr 4) { throttle ->
                        wakeFluids(world, mine, cells, asItStood, owns) { throttle.yieldIfSpent { owned.clear() } }
                    }
                }
            }
        }.awaitAll()
    }
}

/** One fluid tick for each of [cells] the freeze stopped mid-move, on the region that owns it, flag down. */
internal suspend fun StructureRestorer.wakeCells(cells: List<BlockPos>) {
    if (cells.isEmpty()) return
    coroutineScope {
        groupByTile(cells) { it }.map { group ->
            async {
                val anchor = group.first()
                withContext(services.schedulers.region(HolderId.Block(anchor.world, anchor.x, anchor.y, anchor.z))) {
                    val world = worldOf(anchor.world) ?: return@withContext
                    if (!world.isChunkLoaded(anchor.x shr 4, anchor.z shr 4)) return@withContext
                    services.governor.throttled(world, anchor.x shr 4, anchor.z shr 4) { throttle ->
                        for (cell in group) {
                            throttle.yieldIfSpent()
                            val block = world.getBlockAt(cell.x, cell.y, cell.z)
                            if (block.holdsFreeFluid()) runCatching { block.fluidTick() }
                        }
                    }
                }
            }
        }.awaitAll()
    }
}
