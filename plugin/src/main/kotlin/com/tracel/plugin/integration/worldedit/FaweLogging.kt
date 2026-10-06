package com.tracel.plugin.integration.worldedit

import com.fastasyncworldedit.core.extent.processor.ProcessorScope
import com.fastasyncworldedit.core.queue.IBatchProcessor
import com.fastasyncworldedit.core.queue.IChunk
import com.fastasyncworldedit.core.queue.IChunkGet
import com.fastasyncworldedit.core.queue.IChunkSet
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.world.block.BlockTypesCache
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.specifics.block.AIR
import com.tracel.plugin.util.log.Warnings
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("FaweLogging")

/** One ring slot's worth of cells, so a batch takes the fast path. */
private const val BATCH = 4_000

/** `FAWE`'s "this cell is not part of the edit". */
private const val NO_CHANGE = '\u0000'

/**
 * `FAWE`'s own way to see an edit: a batch processor on its chunk queue.
 *
 * `FAWE` writes whole chunk sections at once and bypasses [Extent.setBlock] for most of what it does, and it
 * throws away an extent a plugin wraps around it unless the plugin is listed in its `extent.allowed-plugins`.
 * A processor has neither problem: it is handed, for every chunk about to be written, the blocks the chunk
 * has now and the blocks it is about to get, on `FAWE`'s own threads.
 *
 * Every `FAWE` class `Tracel` touches is in this file, so it must only be loaded when `FAWE` is running.
 */
internal object FaweLogging {
    /** Adds a processor to [extent], which must be (or sit on top of) `FAWE`'s chunk queue. */
    fun attach(extent: Extent, services: TracelServices, world: WorldId, player: UUID) {
        extent.addProcessor(ChunkLogger(services, world, HolderId.Player(player)))
    }
}

private class ChunkLogger(
    private val services: TracelServices,
    private val world: WorldId,
    private val by: HolderId.Player,
) : IBatchProcessor {
    override fun processSet(chunk: IChunk, get: IChunkGet, set: IChunkSet): IChunkSet {
        try {
            record(chunk, get, set)
        } catch (failure: Throwable) {
            Warnings.once(logger, "chunk") { "a FAWE edit could not be logged: $failure" }
            logger.log(Level.FINE, "FAWE chunk not logged", failure)
        }
        return set
    }

    override fun construct(child: Extent): Extent? = null

    override fun getScope(): ProcessorScope = ProcessorScope.READING_BLOCKS

    private fun record(chunk: IChunk, get: IChunkGet, set: IChunkSet) {
        if (set.isEmpty) return
        val buffer = EditBuffer(world)
        val baseX = chunk.x shl 4
        val baseZ = chunk.z shl 4
        val epochMillis = System.currentTimeMillis()
        for (layer in set.minSectionPosition..set.maxSectionPosition) {
            if (!set.hasSection(layer)) continue
            val after = set.loadIfPresent(layer) ?: continue
            val before = if (get.hasSection(layer)) get.load(layer) else null
            val baseY = layer shl 4
            for (i in after.indices) {
                val to = after[i]
                if (to == NO_CHANGE) continue
                val from = before?.get(i)
                if (from == to) continue
                val afterShape = shapeOf(to) ?: continue
                val beforeShape = if (from == null || from == NO_CHANGE) AIR else shapeOf(from) ?: continue
                buffer.note(baseX + (i and 15), baseY + (i shr 8), baseZ + ((i shr 4) and 15), beforeShape, afterShape)
                if (buffer.size >= BATCH) send(buffer, epochMillis)
            }
        }
        send(buffer, epochMillis)
    }

    private fun send(buffer: EditBuffer, epochMillis: Long) {
        for ((action, edits) in buffer.drain()) {
            for (slice in edits.chunked(BATCH)) {
                services.shape.edits(action, CauseKind.PLAYER_ACTION, by, world, slice, epochMillis)
            }
        }
    }

    private fun shapeOf(ordinal: Char): BlockShape? =
        BlockTypesCache.states.getOrNull(ordinal.code)?.let(::logShape)
}
