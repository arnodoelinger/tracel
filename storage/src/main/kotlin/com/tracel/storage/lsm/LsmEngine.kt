package com.tracel.storage.lsm

import com.tracel.storage.lsm.read.LsmSnapshot
import com.tracel.storage.lsm.read.SegmentRun
import com.tracel.storage.lsm.segment.*
import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.lsm.state.Retirement
import com.tracel.storage.lsm.state.Version
import com.tracel.storage.lsm.state.replayWals
import com.tracel.storage.lsm.write.MemTable
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.lsm.write.WalSet
import com.tracel.storage.spi.Dropped
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.EngineSnapshot
import com.tracel.storage.spi.HistorySegment
import com.tracel.storage.spi.Rewritten
import com.tracel.storage.spi.EngineStats
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * The hand-rolled candidate: a log-structured store in `Kotlin` and `FFM`, with no native code
 * of ours and none of anyone else's.
 *
 * A write goes to a write-ahead log and an off-heap skip list. When the skip list fills it is
 * frozen and a maintenance thread writes it out as an immutable, `mmap`ed sorted segment; when
 * segments pile up at a level they are merged down into one.
 *
 * A read merges the live skip lists and every segment, resolving each key to its newest version
 * at or below the reader's snapshot. Publishing a flush or a compaction is one atomic manifest
 * rename, which is what makes a crash at any instant recover to a consistent prefix.
 *
 * This class is the lock and the [version] field, and almost nothing else. Every state change has
 * the same shape — compute outside the lock, then take it, swap the version, publish the manifest,
 * retire what the swap replaced — and that last half is the part a crash has to survive.
 *
 * @param directory where to put the segments and logs
 * @param config how to size the memtables and when to sync the logs
 *
 * @see Version
 * @see SegmentFile
 * @see Retirement
 * @see LsmSnapshot
 * @see planCompaction
 * @see replayWals
 */
class LsmEngine(
    private val directory: Path,
    private val config: LsmConfig = LsmConfig(),
) : KeyValueEngine {
    override val name: String = "lsm"

    private val logger = Logger.getLogger(LsmEngine::class.java.name)

    private val lock = ReentrantLock()
    private val flushed = lock.newCondition()

    private val flusher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tracel-Storage-Flush").apply { isDaemon = true }
    }
    private val compactor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tracel-Storage-Compaction").apply { isDaemon = true }
    }
    private val syncer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Tracel-Storage-Sync").apply { isDaemon = true }
    }

    private val sequence = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val writeCount = AtomicLong(0)
    private val flushCount = AtomicLong(0)
    private val compactionCount = AtomicLong(0)

    private val blocks = BlockCache()
    private val segmentWriter = SegmentWriter(directory, blocks, config)
    private val retirement = Retirement()

    private val open = ArrayList<SegmentReader>()

    private val writing = ConcurrentHashMap.newKeySet<Long>()

    private var nextFileId: Long = 1
    private var logs: WalSet

    @Volatile
    private var failure: Throwable? = null

    private var closed = false

    @Volatile
    private var version: Version

    // region Startup and recovery
    init {
        Files.createDirectories(directory)
        Files.createDirectories(Manifest.segmentsDirectory(directory))
        val manifest = Manifest.read(directory)
        Manifest.sweep(directory, manifest)

        val onDisk = manifest.segments.map { meta ->
            SegmentFile.open(Manifest.segmentPath(directory, meta.id), meta, blocks)
        }
        val recovered = replayWals(directory, manifest, config, segmentWriter)

        open += onDisk
        open += recovered.sealed
        sequence.set(recovered.lastSequence)
        nextFileId = recovered.nextFileId

        recovered.active.walId = nextFileId++
        logs = WalSet(directory, config.sync, recovered.active.walId)
        version = Version(
            recovered.active,
            emptyList(),
            onDisk + recovered.sealed,
            recovered.lastSequence,
            recovered.lastSequence,
            generation.get(),
        )
        publish()
        // Replay can leave more level-0 segments than the fanout allows. Nothing has been written
        // since, so nothing else will notice until it does.
        if (recovered.sealed.isNotEmpty()) compactor.execute { compactWhileNeeded() }
        // An interval policy only synced inside a later write: one write and then silence stayed unsynced
        (config.sync as? SyncPolicy.Interval)?.let { policy ->
            syncer.scheduleWithFixedDelay(
                { runCatching { if (!closed) sync() } },
                policy.millis,
                policy.millis,
                TimeUnit.MILLISECONDS
            )
        }
    }

    // endregion

    // region The writer's door

    override fun write(batch: MutationBatch, durable: Boolean) {
        if (batch.isEmpty()) return

        lock.withLock {
            check(!closed) { "storage engine is closed" }
            check(failure == null) { "storage engine stopped taking writes after an I/O error: ${failure?.message}" }
            if (!fits(version.active, batch)) rotate(batch)
            check(fits(version.active, batch)) {
                "a ${needs(batch)}-byte batch does not fit a freshly rotated " +
                        "${version.active.capacity}-byte memtable — the size accounting is wrong"
            }

            val seq = sequence.incrementAndGet()
            try {
                logs.append(seq, batch)
            } catch (e: Throwable) {
                failure = e
                throw e
            }

            // fits() above already reserved room for this exact batch under the same lock, so
            // put() failing here would mean two batches raced into one memtable without a
            // rotation between them. The check() is there to turn that bug into a loud crash
            // instead of a silently truncated write.
            val table = version.active
            batch.forEach { key, value ->
                check(table.put(key, value, seq)) {
                    "memtable overflowed mid-batch — the fit check above exists so this cannot happen"
                }
            }
            version.lastSequence = seq
            writeCount.incrementAndGet()

            if (durable) {
                try {
                    logs.syncPerPolicy()
                } catch (e: Throwable) {
                    failure = e
                    throw e
                }
            }

            // A batch too big for an ordinary memtable got one sized for it. Send that one on its
            // way now rather than letting the server run on an oversized table for however long it
            // takes to fill — the write-ahead log tracks the table, and a table that does not
            // rotate is a log that does not get retired.
            if (table.capacity > config.memtableBytes) rotate(null)
        }
    }

    override fun snapshot(): EngineSnapshot {
        while (true) {
            val pinned = version
            val at = pinned.lastSequence
            retirement.pin(pinned.generation, at)
            if (version === pinned) return LsmSnapshot(this, pinned, at, pinned.generation)
            retirement.release(pinned.generation, at)
        }
    }

    internal fun releaseSnapshot(generation: Long, at: Long) {
        retirement.release(generation, at)
        if (lock.tryLock()) {
            try {
                retirement.sweep()
            } finally {
                lock.unlock()
            }
        }
    }

    override fun sync() {
        lock.withLock { logs.sync() }
    }

    override fun stats(): EngineStats {
        val current = version
        return EngineStats(
            syncs = logs.syncs,
            liveBytes = current.segments.sumOf { it.meta.fileBytes },
            segmentCount = current.segments.size,
            memtableBytes = current.active.bytesUsed + current.frozen.sumOf { it.bytesUsed },
            walBytes = logs.bytes,
            writes = writeCount.get(),
            flushes = flushCount.get(),
            compactions = compactionCount.get(),
        )
    }

    // endregion

    // region Lifecycle

    override fun compactEverything() {
        flushNow()
        compactor.submit { compactWhileNeeded() }.get()
        quiesce()
    }

    override fun flush() = flushNow()

    override fun history(category: Int): List<HistorySegment> =
        version.segments.filter { it.meta.category == category }.map { reader ->
            val meta = reader.meta
            val start = meta.window * config.windowMillis
            HistorySegment(
                meta.id, meta.category, meta.window, start, start + config.windowMillis,
                meta.entries.toLong(), meta.fileBytes,
            )
        }.sortedWith(compareBy({ it.window }, { it.id }))

    override fun dropHistory(category: Int, endedBefore: Long): Dropped {
        require(category != SegmentClassifier.STATE) { "state is not history, and is never dropped" }
        return compactor.submit<Dropped> { dropNow(category, endedBefore) }.get()
    }

    override fun <T> readSegment(id: Long, prefix: ByteArray, read: (EngineCursor) -> T): T? {
        val snapshot = snapshot() as LsmSnapshot
        snapshot.use { snapshot ->
            return snapshot.scanSegment(id, prefix)?.use(read)
        }
    }

    override fun rewriteSegment(id: Long, keep: (ByteArray, MemorySegment?) -> Boolean): Rewritten =
        compactor.submit<Rewritten> { rewriteNow(id, keep) }.get()

    override fun wipe() {
        quiesce()
        lock.withLock {
            val kept = version.lastSequence
            val fresh = MemTable(config.memtableBytes)
            fresh.walId = nextFileId++
            logs.restartAt(fresh.walId)

            val at = generation.incrementAndGet()
            val discardedSegments = version.segments
            val discardedTables = buildList {
                add(version.active)
                addAll(version.frozen)
            }
            version = Version(fresh, emptyList(), emptyList(), kept, kept, at)
            for (segment in discardedSegments) retirement.retire(segment, at)
            for (table in discardedTables) retirement.retire(table, at)
            publish()
        }
        quiesce()
    }

    /** Waits out every pending flush and compaction. Tests and shutdown. */
    fun quiesce() {
        flusher.submit { }.get()
        compactor.submit { }.get()
    }

    /** Flushes the active memtable and waits for it. Tests and shutdown. */
    fun flushNow() {
        lock.withLock { if (version.active.entries > 0) rotate(null) }
        quiesce()
        quiesce()
    }

    override fun close() = shutdown(seal = true)

    internal fun halt() = shutdown(seal = false)

    private fun shutdown(seal: Boolean) {
        // Three sections, and the maintenance threads have to stop between the first two: sealing
        // while a flush is still running would publish two versions of the same table.
        lock.withLock {
            if (closed) return
            closed = true
            logs.close()
        }
        syncer.shutdownNow()
        flusher.shutdown()
        flusher.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        compactor.shutdown()
        compactor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        lock.withLock {
            if (seal) runCatching { sealOnShutdown() }
            open.forEach { runCatching { it.close() } }
            retirement.closeAll()
            version.closeMemTables()
        }
    }

    private fun sealOnShutdown() {
        val pending = buildList {
            if (version.active.entries > 0) add(version.active)
            addAll(version.frozen.filter { it.entries > 0 })
        }
        if (pending.isEmpty()) {
            if (logs.ids.isEmpty()) return
            logs.clear()
            publish()
            return
        }
        // Oldest first: a later segment has to carry the higher id so it shadows the earlier one
        val written = pending.sortedBy { it.minSequence }
            .flatMap { segmentWriter.seal(it, { nextFileId++ }, horizon = Long.MAX_VALUE) }
        open += written
        logs.clear()
        version = Version(
            version.active,
            version.frozen,
            version.segments + written,
            version.lastSequence,
            version.lastSequence,
            generation.incrementAndGet(),
        )
        publish()
    }

    // endregion

    // region The write path, all under lock

    private fun fits(table: MemTable, batch: MutationBatch): Boolean =
        table.bytesUsed + needs(batch) <= table.capacity

    private fun needs(batch: MutationBatch): Long = batch.byteSize() + batch.size * MemTable.MAX_ENTRY_OVERHEAD

    private fun rotate(oversized: MutationBatch?) {
        var waited = 0
        while (version.frozen.size >= config.maxFrozenMemtables && waited < FLUSH_WAIT_MILLIS) {
            flushed.await(FLUSH_POLL_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            waited += FLUSH_POLL_MILLIS
        }

        val needed = oversized?.let(::needs) ?: 0L
        val frozen = version.active
        val fresh = MemTable(maxOf(config.memtableBytes, needed * 2))
        fresh.walId = nextFileId++
        logs.rollTo(fresh.walId)

        version = version.freezing(frozen, fresh, generation.incrementAndGet())
        Manifest(version.durableSequence, nextFileId, logs.ids, version.segments.map { it.meta }).write(directory)
        flusher.execute { flush(frozen) }
    }

    // endregion

    // region The maintenance threads

    private fun flush(frozen: MemTable) {
        try {
            val ids = ArrayList<Long>()
            try {
                val readers = segmentWriter.seal(frozen, { reserveSegmentId().also { ids += it } }, retirement.horizon())

                lock.withLock {
                    if (closed) {
                        readers.forEach { runCatching { it.close() } }
                        return
                    }
                    open += readers
                    logs.forget(frozen.walId)
                    val at = generation.incrementAndGet()
                    version = version.flushed(frozen, readers, at)
                    retirement.retire(frozen, at)
                    writing -= ids.toSet()
                    publish()
                    flushed.signalAll()
                }
            } finally {
                writing -= ids.toSet()
            }
            flushCount.incrementAndGet()
            runCatching { compactor.execute { compactWhileNeeded() } }
        } catch (e: Throwable) {
            // The write-ahead log still holds every one of these writes, so a failed flush costs
            // memory and a longer recovery, not data, as long as it is tried again rather than dropped.
            logger.log(Level.SEVERE, "flush failed; the write-ahead log still holds this data, retrying", e)
            if (!closed) {
                runCatching {
                    flusher.execute {
                        Thread.sleep(FLUSH_RETRY_MILLIS)
                        if (!closed) flush(frozen)
                    }
                }
            }
        }
    }

    private fun compactWhileNeeded() {
        while (true) {
            val plan = lock.withLock {
                if (closed) return
                planCompaction(version, config, config.windowOf(config.clock())) ?: return
            }
            if (!compact(plan)) return
        }
    }

    private fun compact(plan: CompactionPlan): Boolean {
        try {
            val id = reserveSegmentId()
            val reader = try {
                segmentWriter.write(
                    runs = Array(plan.inputs.size) { SegmentRun(plan.inputs[it]) },
                    id = id,
                    level = plan.level + 1,
                    expectedEntries = plan.expectedEntries,
                    horizon = retirement.horizon(),
                    dropTombstones = plan.dropTombstones,
                    category = plan.category,
                    window = plan.window,
                )
            } catch (e: Throwable) {
                writing -= id
                throw e
            }

            lock.withLock {
                if (closed) {
                    writing -= id
                    runCatching { reader.close() }
                    return false
                }
                open += reader
                val at = generation.incrementAndGet()
                version = version.compacted(plan.inputs, listOf(reader), at)
                open.removeAll(plan.inputs.toSet())
                plan.inputs.forEach { retirement.retire(it, at) }
                writing -= id
                publish()
            }
            compactionCount.incrementAndGet()
            return true
        } catch (e: Throwable) {
            // Nothing was published, so the previous manifest is still in force and the inputs are
            // all still live. The orphaned output gets swept on the next publish or restart.
            logger.log(Level.SEVERE, "compaction failed; the previous manifest is still in force", e)
            return false
        }
    }

    private fun reserveSegmentId(): Long = lock.withLock { nextFileId++.also { writing += it } }

    private fun publish() {
        val manifest = Manifest(
            lastSequence = version.durableSequence,
            nextFileId = nextFileId,
            walIds = logs.ids,
            segments = version.segments.map { it.meta },
        )
        manifest.write(directory)
        retirement.sweep()
        runCatching { Manifest.sweep(directory, manifest, writing) }
    }

    private fun dropNow(category: Int, endedBefore: Long): Dropped = lock.withLock {
        if (closed) return@withLock Dropped(0, 0, 0)
        val doomed = version.segments.filter {
            it.meta.category == category && (it.meta.window + 1) * config.windowMillis <= endedBefore
        }
        if (doomed.isEmpty()) return@withLock Dropped(0, 0, 0)
        val at = generation.incrementAndGet()
        version = version.compacted(doomed, emptyList(), at)
        open.removeAll(doomed.toSet())
        doomed.forEach { retirement.retire(it, at) }
        publish()
        Dropped(doomed.size, doomed.sumOf { it.meta.entries.toLong() }, doomed.sumOf { it.meta.fileBytes })
    }

    private fun rewriteNow(id: Long, keep: (ByteArray, MemorySegment?) -> Boolean): Rewritten {
        val old = lock.withLock { if (closed) null else version.segments.firstOrNull { it.meta.id == id } }
            ?: return Rewritten(0, 0)

        var kept = 0
        var removed = 0L
        val census = SegmentRun(old)
        census.seek(EMPTY_KEY)
        while (census.valid) {
            if (keep(census.userKeyBytes(), census.value())) kept++ else removed++
            census.next()
        }
        if (removed == 0L) return Rewritten(0, 0)

        val replacement = if (kept == 0) null else {
            val newId = reserveSegmentId()
            try {
                segmentWriter.write(
                    arrayOf(SegmentRun(old)), newId, old.meta.level, kept, retirement.horizon(),
                    category = old.meta.category, window = old.meta.window,
                    accept = { run -> keep(run.userKeyBytes(), run.value()) },
                )
            } catch (e: Throwable) {
                writing -= newId
                throw e
            }
        }
        lock.withLock {
            if (closed) {
                replacement?.let { runCatching { it.close() } }
                return Rewritten(0, 0)
            }
            replacement?.let { open += it }
            val at = generation.incrementAndGet()
            version = version.compacted(listOf(old), listOfNotNull(replacement), at)
            open.remove(old)
            retirement.retire(old, at)
            replacement?.let { writing -= it.meta.id }
            publish()
        }
        return Rewritten(removed, old.meta.fileBytes - (replacement?.meta?.fileBytes ?: 0))
    }

    // endregion

    private companion object {
        val EMPTY_KEY = ByteArray(0)

        const val FLUSH_WAIT_MILLIS = 2000
        const val FLUSH_RETRY_MILLIS = 1000L
        const val FLUSH_POLL_MILLIS = 25
        const val SHUTDOWN_WAIT_SECONDS = 30L
    }
}
