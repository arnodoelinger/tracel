package com.tracel.storage.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.CaptureSlot
import com.tracel.storage.intern.Interning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** One pass. Returns how many events it applied. Exposed so tests can drain deterministically. */
    suspend fun drainOnce(): Int {
        val events = collect()
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

    /** Copies published events out of the ring and hands the slots straight back. */
    private fun collect(): List<RawEvent> {
        val out = ArrayList<RawEvent>()
        var cursor = ring.consumerCursor()
        val payload = ring.payload

        while (out.size < maxBatch) {
            if (!ring.isPublished(cursor)) break
            val at = ring.payloadOffset(cursor)
            when (val type = CaptureSlot.type(payload, at)) {
                CaptureSlot.RELEASE -> {
                    out += RawEvent(
                        CaptureSlot.cause(payload, at),
                        CaptureSlot.causedBy(payload, at),
                        CaptureSlot.epochMillis(payload, at),
                        intArrayOf(CaptureSlot.releaseFrom(payload, at), CaptureSlot.releaseTo(payload, at)),
                        EMPTY_INTS,
                        EMPTY_LONGS,
                        release = true,
                    )
                    cursor += 1
                }

                CaptureSlot.HEADER -> {
                    val count = CaptureSlot.deltaCount(payload, at)
                    val holders = IntArray(count)
                    val itemKeys = IntArray(count)
                    val amounts = LongArray(count)
                    for (i in 0 until count) {
                        val slot = ring.payloadOffset(cursor + 1 + i)
                        holders[i] = CaptureSlot.holderId(payload, slot)
                        itemKeys[i] = CaptureSlot.itemKeyId(payload, slot)
                        amounts[i] = CaptureSlot.delta(payload, slot)
                    }
                    out += RawEvent(
                        CaptureSlot.cause(payload, at),
                        CaptureSlot.causedBy(payload, at),
                        CaptureSlot.epochMillis(payload, at),
                        holders,
                        itemKeys,
                        amounts,
                        release = false,
                    )
                    cursor += 1L + count
                }

                else -> error("ring slot $cursor holds type $type where an event was expected — the ring is corrupt")
            }
        }

        if (out.isNotEmpty()) ring.releaseSlots(cursor)
        return out
    }

    private suspend fun apply(event: RawEvent) {
        val cause = CauseKind.entries[event.cause]
        if (event.release) {
            val from = storage.read { resolveHolder(this, event.holders[0]) } ?: return
            val to = storage.read { resolveHolder(this, event.holders[1]) } ?: return
            releaseSink(from, to, event.epochMillis, cause, causedBy(event))
            return
        }
        val deltas = storage.read { resolve(this, event) }
        if (deltas.isNotEmpty()) sink(deltas, event.epochMillis, cause, causedBy(event))
    }

    private fun resolveHolder(unit: StorageUnit, id: Int): HolderId? {
        val real = interning.canonical(unit, id)
        return if (real == 0) null else interning.resolveHolder(unit, real)
    }

    private fun resolve(unit: StorageUnit, event: RawEvent): List<InventoryDelta> {
        val deltas = ArrayList<InventoryDelta>(event.holders.size)
        for (i in event.holders.indices) {
            val holderId = interning.canonical(unit, event.holders[i])
            val itemKeyId = interning.canonical(unit, event.itemKeys[i])
            if (holderId == 0 || itemKeyId == 0) return emptyList()
            deltas += InventoryDelta(
                interning.resolveHolder(unit, holderId),
                interning.resolveItemKey(unit, itemKeyId),
                event.amounts[i],
            )
        }
        return deltas
    }

    private suspend fun causedBy(event: RawEvent): HolderId? {
        if (event.causedBy == 0) return null
        return storage.read {
            val id = interning.canonical(this, event.causedBy)
            if (id == 0) null else interning.resolveHolder(this, id)
        }
    }

    /** One event, copied out of the ring so the slots can go back to the producers immediately. */
    private class RawEvent(
        val cause: Int,
        val causedBy: Int,
        val epochMillis: Long,
        val holders: IntArray,
        val itemKeys: IntArray,
        val amounts: LongArray,
        val release: Boolean,
    )

    private companion object {
        /** Events per commit. Past this the unit of work gets long enough to hurt lookup latency. */
        const val DEFAULT_MAX_BATCH = 1024

        /** How long an empty ring is left alone. One tick is 50 ms; this is well inside it. */
        const val DEFAULT_IDLE_MILLIS = 2L

        val EMPTY_INTS = IntArray(0)
        val EMPTY_LONGS = LongArray(0)
    }
}
