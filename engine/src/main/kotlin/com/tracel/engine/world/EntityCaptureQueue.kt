package com.tracel.engine.world

import com.tracel.model.transaction.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.entity.EntityShape
import com.tracel.platform.storage.UnitOfWork
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

/** One entity appearing, changing or going away, as the region thread saw it. */
public data class EntityChange(
    public val action: ActionKind,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val epochMillis: Long,
    public val at: BlockPos,
    public val entity: UUID,
    public val before: EntityShape?,
    public val after: EntityShape?,
)

/** Batches entity changes into one commit. */
public class EntityCaptureQueue(
    private val coordinator: WorldCaptureCoordinator,
    private val unit: UnitOfWork,
    capacity: Int = DEFAULT_CAPACITY,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
) {
    private val queue = Channel<EntityChange>(capacity)
    private val droppedCount = atomic(0L)
    private val offered = atomic(0L)
    private val settled = atomic(0L)

    public val dropped: Long get() = droppedCount.value

    /**
     * Hands [change] to the writer.
     *
     * @return `false` if the queue was full and it was dropped.
     */
    public fun offer(change: EntityChange): Boolean {
        offered.incrementAndGet()
        if (queue.trySend(change).isSuccess) return true
        settled.incrementAndGet()
        val total = droppedCount.incrementAndGet()
        if (total == 1L || total % DROP_REPORT_EVERY == 0L) {
            logger.warning(
                "Tracel entity capture queue is full — $total entity change(s) have been dropped and " +
                        "cannot be rolled back. The storage thread is not keeping up."
            )
        }
        return false
    }

    /**
     * Waits until everything [offer] accepted before the call has been written (or dropped by the
     * writer). What arrives meanwhile is not waited for: a busy server is never quiet.
     *
     * @return whether it all landed. False means whoever reads the log next is reading a window
     * that does not yet have the last few entity changes in it.
     */
    public suspend fun flush(timeoutMs: Long = 2_000): Boolean {
        val upTo = offered.value
        val deadline = System.currentTimeMillis() + timeoutMs
        while (settled.value < upTo && System.currentTimeMillis() < deadline) {
            delay(1.milliseconds)
        }
        return settled.value >= upTo
    }

    /** Runs until [scope] is canceled, writing whatever has piled up as one unit of work at a time. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        val batch = ArrayList<EntityChange>(maxBatch)
        while (isActive) {
            batch += queue.receive()
            while (batch.size < maxBatch) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch += next
            }
            try {
                unit.atomically {
                    for (i in batch.indices) write(batch[i])
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                writeOneByOne(batch)
            } finally {
                settled.addAndGet(batch.size.toLong())
            }
            batch.clear()
        }
    }

    private suspend fun writeOneByOne(batch: List<EntityChange>) {
        for (change in batch) {
            try {
                unit.atomically { write(change) }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Throwable) {
                val total = droppedCount.incrementAndGet()
                logger.warning(
                    "Tracel could not record a ${change.action} of entity ${change.entity} at ${change.at} " +
                            "(${failure.javaClass.simpleName}: ${failure.message}); $total entity change(s) dropped so far"
                )
            }
        }
    }

    private suspend fun write(change: EntityChange) {
        coordinator.recordEntity(
            change.action,
            change.cause,
            change.causedBy,
            change.epochMillis,
            change.at,
            change.entity,
            change.before,
            change.after,
        )
    }

    private companion object {
        const val DEFAULT_CAPACITY = 1 shl 14
        const val DEFAULT_MAX_BATCH = 512
        const val DROP_REPORT_EVERY = 1_000L
        val logger: Logger = Logger.getLogger("EntityCaptureQueue")
    }
}
