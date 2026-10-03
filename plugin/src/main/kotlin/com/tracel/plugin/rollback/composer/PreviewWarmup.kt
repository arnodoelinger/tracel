package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.structure.block.PalettePaste
import com.tracel.plugin.rollback.structure.throttled
import com.tracel.plugin.util.ownsChunkAt
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.World
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_WARM_CHUNKS = 1_024
private const val HOLD_MILLIS = 300_000L

private val logger = Logger.getLogger("TracelPreviewWarmup")

/**
 * Quietly get ready for the rollback a `#preview` is about to be followed by.
 *
 * Writes nothing and says nothing. Parses the block states the plan will write, builds the fluid tables,
 * loads the chunks it touches, and pins them for [HOLD_MILLIS] so the real run starts on warm ground.
 */
internal suspend fun warmForPreview(services: TracelServices, planned: Planned) {
    try {
        services.warmRollback()
        val steps = planned.composite.create + planned.composite.destroy
        for (step in steps) {
            if (step is StructureStep.SetBlock) {
                BlockDataCache.of(step.target.data)
                BlockDataCache.of(step.expected.data)
            }
        }

        val byWorld = steps.groupBy { it.at.world }
        val pinned = ArrayList<Triple<World, Int, Int>>()
        var budget = MAX_WARM_CHUNKS
        coroutineScope {
            for ((id, group) in byWorld) {
                val world = worldOf(id) ?: continue
                val chunks = group.mapTo(LinkedHashSet()) { (it.at.x shr 4) to (it.at.z shr 4) }.take(budget)
                budget -= chunks.size
                chunks.map { (cx, cz) ->
                    async {
                        val loaded = runCatching { world.getChunkAtAsync(cx, cz).await() }.isSuccess
                        if (loaded && runCatching { world.addPluginChunkTicket(cx, cz, services.plugin) }.getOrDefault(false)) {
                            synchronized(pinned) { pinned += Triple(world, cx, cz) }
                        }
                    }
                }.awaitAll()
            }
        }

        readPass(services, steps, byWorld)
        warmMaterial(services, planned)

        if (pinned.isEmpty()) return
        try {
            delay(HOLD_MILLIS.milliseconds)
        } finally {
            for ((world, cx, cz) in pinned) runCatching { world.removePluginChunkTicket(cx, cz, services.plugin) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        logger.log(Level.FINE, "preview warm-up gave up", failure)
    }
}

private suspend fun readPass(
    services: TracelServices,
    steps: List<StructureStep>,
    byWorld: Map<WorldId, List<StructureStep>>,
) = coroutineScope {
    for ((id, group) in byWorld) {
        val world = worldOf(id) ?: continue
        val anchor = group.first().at
        launch {
            withContext(services.schedulers.region(HolderId.Block(id, anchor.x, anchor.y, anchor.z))) {
                runCatching { PalettePaste.tryOpen(world) }
            }
        }
    }
    for (cell in steps.groupBy { it.at.regionKey() }.values) {
        val anchor = cell.first().at
        val world = worldOf(anchor.world) ?: continue
        launch {
            withContext(services.schedulers.region(HolderId.Block(anchor.world, anchor.x, anchor.y, anchor.z))) {
                services.governor.throttled(world, anchor.x shr 4, anchor.z shr 4) { throttle ->
                    for (step in cell) {
                        throttle.yieldIfSpent()
                        // Another region's cell by the time we got here: not ours to read
                        if (!ownsChunkAt(world, step.at.x, step.at.z)) continue
                        runCatching { world.getBlockAt(step.at.x, step.at.y, step.at.z).type }
                    }
                }
            }
        }
    }
}

private suspend fun warmMaterial(services: TracelServices, planned: Planned) {
    runCatching {
        val deltas = services.restorer.deltasFor(planned.composite.material, planned.target)
        services.restorer.preflight(deltas)
    }.onFailure { if (it is CancellationException) throw it }
}
