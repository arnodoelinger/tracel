package com.tracel.storage

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.capture.CaptureRing
import com.tracel.storage.intern.Interning
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The one door into the store. You better not touch storage at all.
 *
 * Every port goes through [read] or [write] and none of them cares which thread its caller
 * happened to be on. Only one unit of work is ever open at a time, guarded by a suspending
 * [Mutex].
 */
class TracelStorage private constructor(
    val engine: KeyValueEngine,
    val interning: Interning,
    val ring: CaptureRing,
    private val executor: ExecutorService,
    val dispatcher: CoroutineDispatcher,
) : UnitOfWork, AutoCloseable {
    private val lock = Mutex()
    private val writer = SingleWriterGuard()

    /** Reads inside the current unit of work, or opens a throwaway one if there is none. */
    suspend fun <T> read(block: StorageUnit.() -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        return if (open != null) open.joined().block() else readOnly(block)
    }

    /** Like [read], but also asserts that every write really does land on one and the same thread. */
    suspend fun <T> write(block: StorageUnit.() -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        return if (open != null) {
            writer.checkIn()
            open.joined().block()
        } else {
            unit { writer.checkIn(); block() }
        }
    }

    override suspend fun <T> atomically(block: suspend () -> T): T =
        if (currentCoroutineContext()[OpenUnit] != null) block() else suspendingUnit(block)

    /**
     * Runs [block] inside one unit of work and commits it as one batch.
     *
     * This is what the drainer calls once per drained batch: the whole batch of captured events
     * becomes one write-ahead log frame, one `fsync`, and one atomic publish, instead of one of
     * each per game event.
     */
    suspend fun <T> batched(block: suspend () -> T): T = suspendingUnit(block)

    private suspend fun <T> readOnly(block: StorageUnit.() -> T): T = withContext(dispatcher) {
        StorageUnit(engine.snapshot(), MutationBatch(), Thread.currentThread()).use(block)
    }

    private suspend fun <T> unit(block: StorageUnit.() -> T): T = lock.withLock {
        withContext(dispatcher) {
            val open = StorageUnit(engine.snapshot(), MutationBatch(), Thread.currentThread())
            open.use {
                val result = withContext(OpenUnit(open, Thread.currentThread())) { open.block() }
                engine.write(open.batch, durable = true)
                result
            }
        }
    }

    private suspend fun <T> suspendingUnit(block: suspend () -> T): T = lock.withLock {
        withContext(dispatcher) {
            val open = StorageUnit(engine.snapshot(), MutationBatch(), Thread.currentThread())
            open.use {
                val result = withContext(OpenUnit(open, Thread.currentThread())) { block() }
                engine.write(open.batch, durable = true)
                result
            }
        }
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Idempotent: an off-heap arena closed twice throws, and closing twice is easy to arrange. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.close()
        ring.close()
        executor.shutdown()
    }

    /** Carries the open unit to everything nested inside it. */
    private class OpenUnit(private val unit: StorageUnit, private val thread: Thread) :
        AbstractCoroutineContextElement(OpenUnit) {
        /**
         * The open unit, once it is established that we are still on the thread that opened it.
         * A unit that hops to a region or entity thread mid-flight and then reaches back into
         * storage would be handing one write batch to two threads at once, so it fails here.
         */
        fun joined(): StorageUnit {
            check(thread === Thread.currentThread()) {
                "unit of work opened on ${thread.name} was re-entered from ${Thread.currentThread().name} — " +
                    "an atomically { } block must not leave the storage thread"
            }
            return unit
        }

        companion object Key : CoroutineContext.Key<OpenUnit>
    }

    companion object {
        /**
         * Opens (or creates) the store at [path].
         *
         * [engineFactory] is the swap seam: the shipped build passes an [LsmEngine], and the
         * benchmarks pass whatever they are measuring.
         */
        fun open(
            path: Path,
            ringSlots: Int = DEFAULT_RING_SLOTS,
            engineFactory: (Path) -> KeyValueEngine = { LsmEngine(it, LsmConfig()) },
        ): TracelStorage {
            val engine = engineFactory(path)
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Tracel-Storage") }
            val interning = Interning()
            StorageUnit(engine.snapshot(), MutationBatch(), Thread.currentThread()).use(interning::restore)
            return TracelStorage(
                engine,
                interning,
                CaptureRing(ringSlots, interning),
                executor,
                executor.asCoroutineDispatcher(),
            )
        }

        const val DEFAULT_RING_SLOTS = 1 shl 16
    }
}
