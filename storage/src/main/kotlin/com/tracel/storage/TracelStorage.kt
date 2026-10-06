package com.tracel.storage

import com.tracel.engine.store.StoreSettings
import com.tracel.storage.lsm.toLsmConfig
import com.tracel.platform.concurrency.SingleWriterGuard
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.capture.CaptureRing
import com.tracel.storage.codec.History
import com.tracel.storage.intern.Interning
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one door into the store. You better not touch storage at all.
 *
 * Every port goes through [read] or [write] and none of them cares which thread its caller
 * happened to be on. Only one unit of work is ever open at a time, guarded by a suspending
 * [Mutex].
 *
 * @see StorageUnit
 * @see Interning
 * @see CaptureRing
 * @see KeyValueEngine
 * @see LsmEngine
 * @see UnitOfWork
 */
class TracelStorage private constructor(
    val engine: KeyValueEngine,
    val interning: Interning,
    val ring: CaptureRing,
    private val executor: ScheduledExecutorService,
    val dispatcher: CoroutineDispatcher,
    private val readerPool: ExecutorService,
    private val readers: CoroutineDispatcher,
) : UnitOfWork, AutoCloseable {
    private val lock = Mutex()
    private val writer = SingleWriterGuard()

    private val closed = AtomicBoolean(false)

    private val replaced = CopyOnWriteArrayList<() -> Unit>()

    /** Reads inside the current unit of work, or opens a throwaway one if there is none. */
    suspend fun <T> read(block: StorageUnit.() -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        return if (open != null) open.joined().block() else readOnly(block)
    }

    /** Like [read], but also asserts that every write really does land on one and the same thread. */
    suspend fun <T> write(block: StorageUnit.() -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        return if (open != null) {
            check(!open.readOnly) { "write inside reading { } — a snapshot is not a unit of work" }
            writer.checkIn()
            open.joined().block()
        } else {
            unit { writer.checkIn(); block() }
        }
    }

    override suspend fun <T> atomically(block: suspend () -> T): T =
        if (currentCoroutineContext()[OpenUnit] != null) block() else suspendingUnit(block)

    override suspend fun <T> reading(block: suspend () -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        if (open != null) return block()
        return withContext(readers) {
            StorageUnit(engine.snapshot(), MutationBatch()).use { unit ->
                withContext(OpenUnit(unit, Thread.currentThread(), readOnly = true)) { block() }
            }
        }
    }

    override suspend fun mark(): Int = read { mark() }

    override suspend fun release(mark: Int) = read { release(mark) }

    override suspend fun rollbackTo(mark: Int) = read { rollbackTo(mark) }

    /**
     * Runs [block] inside one unit of work and commits it as one batch.
     *
     * This is what the drainer calls once per drained batch: the whole batch of captured events
     * becomes one write-ahead log frame, one `fsync`, and one atomic publish, instead of one of
     * each per game event.
     */
    suspend fun <T> batched(block: suspend () -> T): T = suspendingUnit(block)

    /**
     * Runs [block] with no unit of work open anywhere, on the storage thread: the drainer and every other
     * writer wait. For replacing the store wholesale, where one batch landing mid-wipe resurrects old data.
     */
    suspend fun <T> alone(block: () -> T): T = lock.withLock { withContext(dispatcher) { block() } }

    /** Runs [action] whenever the store is replaced wholesale, for whoever caches what was in it. */
    fun afterReplace(action: () -> Unit) {
        replaced += action
    }

    /** Re-reads interned ids after the store was replaced under them. Call inside [alone]. */
    fun reloadInterning() {
        StorageUnit(engine.snapshot(), MutationBatch()).use(interning::reload)
        replaced.forEach { it() }
    }

    /**
     * Runs [last] to its end or for [timeoutMillis], whichever comes first, then closes. Blocks the
     * caller, so shutdown only: that is the one place something has to wait for the last writes.
     *
     * @return whether [last] finished in time.
     */
    fun closeAfter(timeoutMillis: Long, last: suspend () -> Unit): Boolean {
        val done = CountDownLatch(1)
        var finished = false
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                finished = withTimeoutOrNull(timeoutMillis.milliseconds) { last() } != null
            } finally {
                done.countDown()
            }
        }
        done.await(timeoutMillis + CLOSE_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        close()
        return finished
    }

    /** Closes the storage and releases all associated resources. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.close()
        ring.close()
        executor.shutdown()
        readerPool.shutdown()
    }

    private suspend fun <T> readOnly(block: StorageUnit.() -> T): T = withContext(readers) {
        StorageUnit(engine.snapshot(), MutationBatch()).use(block)
    }

    private suspend fun <T> unit(block: StorageUnit.() -> T): T = lock.withLock {
        withContext(dispatcher) {
            val open = StorageUnit(engine.snapshot(), MutationBatch())
            open.use {
                val result = try {
                    withContext(OpenUnit(open, Thread.currentThread())) { open.block() }.also {
                        WriteLog.dump(open.batch)
                        engine.write(open.batch, durable = true)
                    }
                } catch (failure: Throwable) {
                    open.aborted()
                    throw failure
                }
                open.committed()
                result
            }
        }
    }

    private suspend fun <T> suspendingUnit(block: suspend () -> T): T = lock.withLock {
        withContext(dispatcher) {
            val open = StorageUnit(engine.snapshot(), MutationBatch())
            open.use {
                val result = try {
                    withContext(OpenUnit(open, Thread.currentThread())) { block() }.also {
                        WriteLog.dump(open.batch)
                        engine.write(open.batch, durable = true)
                    }
                } catch (failure: Throwable) {
                    open.aborted()
                    throw failure
                }
                open.committed()
                result
            }
        }
    }

    private class OpenUnit(
        private val unit: StorageUnit,
        private val thread: Thread,
        val readOnly: Boolean = false,
    ) : AbstractCoroutineContextElement(OpenUnit) {
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
        const val DEFAULT_RING_SLOTS = StoreSettings.DEFAULT_RING_SLOTS
        const val MAX_READERS = 16
        private const val CLOSE_GRACE_MILLIS = 1_000L

        fun open(path: Path, settings: StoreSettings): TracelStorage =
            open(path, ringSlots = settings.ringSlots, lsm = settings.toLsmConfig())

        fun open(
            path: Path,
            ringSlots: Int = DEFAULT_RING_SLOTS,
            lsm: LsmConfig = LsmConfig(),
            overflowSlots: Int = ringSlots * CaptureRing.OVERFLOW_FACTOR,
            engineFactory: (Path) -> KeyValueEngine = { LsmEngine(it, History.configured(lsm)) },
        ): TracelStorage {
            val engine = engineFactory(path)
            val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "Tracel-Storage") }
            val interning = Interning()
            StorageUnit(engine.snapshot(), MutationBatch()).use(interning::restore)

            // These threads only ever decode records already in memory or in a
            // mapped file; more of them than cores buys queueing, not throughput.
            val readerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_READERS)
            val readerPool = Executors.newFixedThreadPool(readerCount) { runnable ->
                Thread(runnable, "Tracel-Read").apply { isDaemon = true }
            }

            return TracelStorage(
                engine,
                interning,
                CaptureRing(ringSlots, interning, overflowSlots),
                executor,
                executor.asCoroutineDispatcher(),
                readerPool,
                readerPool.asCoroutineDispatcher(),
            )
        }
    }
}
