package com.tracel.storage.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.intern.Interning
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drains the capture ring and turns it back into ledger work — the storage-thread half of the
 * pipeline.
 */
class Drainer(
    private val storage: TracelStorage,
    private val ring: CaptureRing,
    private val interning: Interning,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    private val sink: suspend (List<InventoryDelta>, Long, CauseKind, HolderId?) -> Unit,
    private val releaseSink: suspend (HolderId, HolderId, Long, CauseKind, HolderId?) -> Unit,
    private val worldSink: suspend (BlockEdits) -> Unit,
) {
    private val logger = Logger.getLogger(Drainer::class.java.name)
    private val drainedEvents = AtomicLong(0)
    private val drainedBatches = AtomicLong(0)
    private val rejected = AtomicLong(0)

    /** Events successfully applied. Together with [CaptureRing.dropped], the whole capture story. */
    val events: Long get() = drainedEvents.get()

    /** Events the ledger refused — untracked material, almost always. */
    val refused: Long get() = rejected.get()

    /**
     * Runs until the scope is canceled.
     *
     * Cancellation works because the loop only ever suspends in [delay] and in the unit of work,
     * both of which are cancellable, and because it never holds a cursor or a write batch open
     * across a suspension point that could be canceled mid-flight.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(storage.dispatcher) {
        while (isActive) {
            val drained = try {
                drainOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // If the drain loop dies because of one corrupted batch, the whole
                // capture pipeline goes up in flames. Log it, skip it, and move on.
                logger.log(Level.SEVERE, "drain pass failed; the loop continues", e)
                0
            }
            if (drained == 0) delay(idleMillis.milliseconds)
        }
    }

    /**
     * One pass of drain.
     *
     * @return how many events it applied. Exposed so tests can drain deterministically.
     */
    suspend fun drainOnce(): Int {
        val events = ring.collectPublished(maxBatch)
        if (events.isEmpty()) {
            interning.compactProvisional()
            return 0
        }

        storage.batched {
            for (event in events) {
                // A savepoint per event, so one impossible withdrawal unwinds itself and leaves
                // the other 9421 events in this batch alone.
                val mark = storage.read { mark() }
                try {
                    apply(event)
                    storage.read { release(mark) }
                    drainedEvents.incrementAndGet()
                } catch (e: IllegalStateException) {
                    storage.read { rollbackTo(mark) }
                    rejected.incrementAndGet()
                    logger.log(Level.FINE, "captured event did not apply to the ledger, skipped", e)
                }
            }
        }
        drainedBatches.incrementAndGet()
        return events.size
    }

    private suspend fun apply(event: RingEvent) {
        val cause = CauseKind.entries[event.cause]
        when (event) {
            is RingEvent.World -> {
                val edits = storage.read { resolve(this, event) }
                if (edits.isNotEmpty()) {
                    worldSink(
                        BlockEdits(ActionKind.entries[event.action], cause, causedBy(event), event.epochMillis, edits)
                    )
                }
            }

            is RingEvent.Release -> {
                val from = storage.read { resolveHolder(this, event.fromHolderId) } ?: return
                val to = storage.read { resolveHolder(this, event.toHolderId) } ?: return
                releaseSink(from, to, event.epochMillis, cause, causedBy(event))
            }

            is RingEvent.Items -> {
                val deltas = storage.read { resolve(this, event) }
                if (deltas.isNotEmpty()) sink(deltas, event.epochMillis, cause, causedBy(event))
            }
        }
    }

    private fun resolveHolder(unit: StorageUnit, id: Int): HolderId? {
        val real = interning.canonical(unit, id)
        return if (real == 0) null else interning.resolveHolder(unit, real)
    }

    private fun resolve(unit: StorageUnit, event: RingEvent.World): List<BlockEdit> {
        val worldId = interning.canonical(unit, event.worldId)
        if (worldId == 0) return emptyList()
        val world = interning.resolveWorld(unit, worldId)

        val edits = ArrayList<BlockEdit>(event.befores.size)
        for (i in event.befores.indices) {
            val before = interning.canonical(unit, event.befores[i])
            val after = interning.canonical(unit, event.afters[i])
            if (before == 0 || after == 0) continue
            edits += BlockEdit(
                BlockPos(world, event.coordinates[i * 3], event.coordinates[i * 3 + 1], event.coordinates[i * 3 + 2]),
                BlockShape(interning.resolveBlockData(unit, before)),
                BlockShape(interning.resolveBlockData(unit, after)),
            )
        }
        return edits
    }

    private fun resolve(unit: StorageUnit, event: RingEvent.Items): List<InventoryDelta> {
        val deltas = ArrayList<InventoryDelta>(event.holders.size)
        for (i in event.holders.indices) {
            val holderId = interning.canonical(unit, event.holders[i])
            val itemKeyId = interning.canonical(unit, event.itemKeys[i])
            if (holderId == 0 || itemKeyId == 0) continue
            deltas += InventoryDelta(
                interning.resolveHolder(unit, holderId),
                interning.resolveItemKey(unit, itemKeyId),
                event.amounts[i],
            )
        }
        return deltas
    }

    private suspend fun causedBy(event: RingEvent): HolderId? {
        if (event.causedBy == 0) return null
        return storage.read {
            val id = interning.canonical(this, event.causedBy)
            if (id == 0) null else interning.resolveHolder(this, id)
        }
    }

    private companion object {
        const val DEFAULT_MAX_BATCH = 1024
        const val DEFAULT_IDLE_MILLIS = 2L
    }
}
