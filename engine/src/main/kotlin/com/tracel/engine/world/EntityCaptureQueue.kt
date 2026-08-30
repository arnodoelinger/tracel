package com.tracel.engine.world

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.EntityShape
import com.tracel.platform.storage.UnitOfWork
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
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
    private val pending = atomic(0)

    public val dropped: Long get() = droppedCount.value

    /**
     * Hands [change] to the writer.
     *
     * @return `false` if the queue was full and it was dropped.
     */
    public fun offer(change: EntityChange): Boolean {
        pending.incrementAndGet()
        if (queue.trySend(change).isSuccess) return true
        pending.decrementAndGet()
        droppedCount.incrementAndGet()
        return false
    }

    /** Waits until everything [offer] accepted has been written (or dropped by the writer). */
    public suspend fun flush(timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.value > 0 && System.currentTimeMillis() < deadline) {
            delay(1.milliseconds)
        }
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
                    for (i in batch.indices) {
                        val c = batch[i]
                        coordinator.recordEntity(
                            c.action,
                            c.cause,
                            c.causedBy,
                            c.epochMillis,
                            c.at,
                            c.entity,
                            c.before,
                            c.after,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // One bad NBT used to kill the writer, and every spawn after that vanished
            } finally {
                pending.addAndGet(-batch.size)
            }
            batch.clear()
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 1 shl 14
        const val DEFAULT_MAX_BATCH = 512
    }
}
