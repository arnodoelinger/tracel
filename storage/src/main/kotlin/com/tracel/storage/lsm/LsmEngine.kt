package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.ffm.fsyncDirectory
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.EngineSnapshot
import com.tracel.storage.spi.EngineStats
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock
import java.util.logging.Level
import java.util.logging.Logger

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
 * About ownership... One writer thread, and it's enforced by the caller. Flush and compaction run
 * on their own thread. Readers are unbounded and lock-free, snapshot pins a
 * [Version] and everything it names stays alive and unmapped-from-nobody until it lets go.
 */
class LsmEngine(
    private val directory: Path,
    private val config: LsmConfig = LsmConfig(),
) : KeyValueEngine {
    override val name: String = "lsm"

    private val logger = Logger.getLogger(LsmEngine::class.java.name)
    private val lock = java.util.concurrent.locks.ReentrantLock()

    private val flushed = lock.newCondition()
    private val maintenance = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tracel-Storage-Maintenance").apply { isDaemon = true }
    }

    private val sequence = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val writeCount = AtomicLong(0)
    private val flushCount = AtomicLong(0)
    private val compactionCount = AtomicLong(0)

    private val pinnedGenerations = ConcurrentSkipListMap<Long, AtomicInteger>()

    private val pinnedSequences = ConcurrentSkipListMap<Long, AtomicInteger>()

    private val blocks = BlockCache()

    private var walSyncs = 0L
    private val retired = ArrayList<Pair<SegmentReader, Long>>()
    private val retiredTables = ArrayList<Pair<MemTable, Long>>()
    private val open = ArrayList<SegmentReader>()

    private var nextFileId: Long = 1
    private var walIds = ArrayList<Long>()
    private var wal: Wal
    private var lastSyncMillis = System.currentTimeMillis()
    private var closed = false

    @Volatile
    private var version: Version

    init {
        Files.createDirectories(directory)
        Files.createDirectories(Manifest.segmentsDirectory(directory))
        val manifest = Manifest.read(directory)
        Manifest.sweep(directory, manifest)
        nextFileId = manifest.nextFileId
        walIds = ArrayList(manifest.walIds)

        val readers = manifest.segments.map { meta ->
            SegmentFile.open(Manifest.segmentPath(directory, meta.id), meta, blocks)
        }
        open += readers

        var recovered = manifest.lastSequence
        var active = MemTable(config.memtableBytes)
        val recoveredSegments = ArrayList<SegmentReader>()
        for (id in walIds) {
            val applied = Wal.replay(Manifest.walPath(directory, id), manifest.lastSequence) { seq, key, value ->
                if (!active.put(key, value, seq)) {
                    recoveredSegments += sealDuringRecovery(active)
                    active = MemTable(maxOf(config.memtableBytes, entryBytes(key, value) * 2))
                    check(active.put(key, value, seq)) {
                        "a recovered entry does not fit a memtable sized for it — the accounting is wrong"
                    }
                }
            }
            if (applied > recovered) recovered = applied
        }
        if (active.entries > 0) {
            recoveredSegments += sealDuringRecovery(active)
            active = MemTable(config.memtableBytes)
        }
        sequence.set(recovered)
        open += recoveredSegments

        val walId = nextFileId++
        walIds = arrayListOf(walId)
        active.walId = walId
        wal = Wal.create(Manifest.walPath(directory, walId))
        version = Version(active, emptyList(), readers + recoveredSegments, recovered, recovered, generation.get())
        publish(currentManifest())
        if (recoveredSegments.isNotEmpty()) maintenance.execute { maybeCompact() }
    }

    private fun sealDuringRecovery(table: MemTable): SegmentReader {
        val id = nextFileId++
        val reader = writeSegment(table, id, Long.MAX_VALUE)
        table.close()
        return reader
    }

    private fun entryBytes(key: ByteArray, value: ByteArray?): Long =
        key.size + (value?.size ?: 0) + MemTable.MAX_ENTRY_OVERHEAD

    override fun write(batch: MutationBatch, durable: Boolean) {
        if (batch.isEmpty()) return

        lock.withLock {
            check(!closed) { "storage engine is closed" }
            if (!fits(version.active, batch)) rotate(batch)
            check(fits(version.active, batch)) {
                "a ${needs(batch)}-byte batch does not fit a freshly rotated " +
                    "${version.active.capacity}-byte memtable — the size accounting is wrong"
            }

            val seq = sequence.incrementAndGet()
            wal.append(seq, batch)

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

            if (durable) syncPerPolicy()

            // A batch too big for an ordinary memtable got one sized for it. Send that one on
            // its way now rather than letting the server run on an oversized table for however
            // long it takes to fill — the write-ahead log tracks the table, and a table that
            // does not rotate is a log that does not get retired.
            if (table.capacity > config.memtableBytes) rotate(null)
        }
    }

    override fun snapshot(): EngineSnapshot {
        lock.withLock {
            val pinned = version
            val at = pinned.lastSequence
            val generation = pinned.generation
            pinnedGenerations.computeIfAbsent(generation) { AtomicInteger(0) }.incrementAndGet()
            pinnedSequences.computeIfAbsent(at) { AtomicInteger(0) }.incrementAndGet()
            return LsmSnapshot(this, pinned, at, generation)
        }
    }

    override fun sync() {
        lock.withLock { wal.sync() }
    }

    override fun compactEverything() {
        flushNow()
        maybeCompact()
        quiesce()
    }

    override fun wipe() {
        quiesce()
        lock.withLock {
            val kept = version.lastSequence
            val fresh = MemTable(config.memtableBytes)
            val walId = nextFileId++
            wal.sync()
            wal.close()
            walSyncs += wal.syncs
            walIds = arrayListOf(walId)
            wal = Wal.create(Manifest.walPath(directory, walId))
            fresh.walId = walId

            val at = generation.incrementAndGet()
            val discarded = version.segments
            val discardedTables = buildList {
                add(version.active)
                addAll(version.frozen)
            }
            version = Version(fresh, emptyList(), emptyList(), kept, kept, at)
            for (segment in discarded) retired += segment to at
            for (table in discardedTables) retiredTables += table to at
            publish(currentManifest())
        }
        quiesce()
    }

    override fun stats(): EngineStats {
        val current = version
        return EngineStats(
            syncs = walSyncs + wal.syncs,
            liveBytes = current.segments.sumOf { it.meta.fileBytes },
            segmentCount = current.segments.size,
            memtableBytes = current.active.bytesUsed + current.frozen.sumOf { it.bytesUsed },
            walBytes = wal.bytes,
            writes = writeCount.get(),
            flushes = flushCount.get(),
            compactions = compactionCount.get(),
        )
    }

    /** Waits out every pending flush and compaction. Tests and shutdown. */
    fun quiesce() {
        maintenance.submit { }.get()
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
        // Three separate lock sections
        lock.withLock {
            if (closed) return
            closed = true
            wal.sync()
            wal.close()
        }
        maintenance.shutdown()
        maintenance.awaitTermination(30, TimeUnit.SECONDS)
        lock.withLock {
            if (seal) runCatching { sealOnShutdown() }
            open.forEach { runCatching { it.close() } }
            retired.forEach { (reader, _) -> runCatching { reader.close() } }
            retiredTables.forEach { (table, _) -> runCatching { table.close() } }
            version.closeMemTables()
        }
    }

    private fun sealOnShutdown() {
        val pending = buildList {
            if (version.active.entries > 0) add(version.active)
            addAll(version.frozen.filter { it.entries > 0 })
        }
        if (pending.isEmpty()) {
            if (walIds.isEmpty()) return
            walIds.clear()
            publish(currentManifest())
            return
        }
        val written = ArrayList<SegmentReader>(pending.size)
        // Oldest first: a later segment has to carry the higher id so it shadows the earlier one
        for (table in pending.asReversed()) written += writeSegment(table, nextFileId++, Long.MAX_VALUE)
        open += written
        walIds.clear()
        version = Version(
            version.active,
            version.frozen,
            version.segments + written,
            version.lastSequence,
            version.lastSequence,
            generation.incrementAndGet(),
        )
        publish(currentManifest())
    }

    // Write path, all under lock

    private fun syncPerPolicy() {
        when (val policy = config.sync) {
            SyncPolicy.EveryBatch -> wal.sync()
            SyncPolicy.Never -> Unit
            is SyncPolicy.Interval -> {
                val now = System.currentTimeMillis()
                if (now - lastSyncMillis >= policy.millis) {
                    wal.sync()
                    lastSyncMillis = now
                }
            }
        }
    }

    private fun fits(table: MemTable, batch: MutationBatch): Boolean =
        table.bytesUsed + needs(batch) <= table.capacity

    /** An upper bound on what [batch] will cost a memtable — see [MemTable.MAX_ENTRY_OVERHEAD]. */
    private fun needs(batch: MutationBatch): Long = batch.byteSize() + batch.size * MemTable.MAX_ENTRY_OVERHEAD

    /**
     * Freezes the active memtable, starts a fresh log, and hands the frozen one to maintenance.
     *
     * [oversized] is the batch that would not fit. A batch bigger than a whole memtable gets a
     * memtable sized for it — the alternative is rotating forever and never making room.
     */
    private fun rotate(oversized: MutationBatch?) {
        var waited = 0
        while (version.frozen.size >= config.maxFrozenMemtables && waited < FLUSH_WAIT_MILLIS) {
            flushed.await(25, TimeUnit.MILLISECONDS)
            waited += 25
        }

        val needed = oversized?.let(::needs) ?: 0L
        val frozen = version.active
        val fresh = MemTable(maxOf(config.memtableBytes, needed * 2))

        val walId = nextFileId++
        wal.sync()
        wal.close()
        walSyncs += wal.syncs
        walIds += walId
        wal = Wal.create(Manifest.walPath(directory, walId))
        fresh.walId = walId

        version = version.freezing(frozen, fresh, generation.incrementAndGet())
        maintenance.execute { flush(frozen) }
    }

    // Maintenance thread

    /**
     * One memtable, written out as an immutable level-0 segment.
     *
     * Collapses versions on the way: a running total rewritten once per captured event arrives
     * here as thousands of entries for one key, and writing all of them would put that cost on
     * every future read of the segment as well as on the file size. [horizon] is the sequence
     * below which only the newest version still matters.
     */
    private fun writeSegment(table: MemTable, id: Long, horizon: Long): SegmentReader {
        val path = Manifest.segmentPath(directory, id)
        val meta = SegmentFile.Writer(path, table.entries).use { writer ->
            var node = table.seek(ByteArray(0))
            var lastKey: ByteArray? = null
            var keptBelowHorizon = false
            while (node != 0L) {
                val userKey = table.userKeyBytes(node)
                if (lastKey == null || !userKey.contentEquals(lastKey)) {
                    lastKey = userKey
                    keptBelowHorizon = false
                }
                val visible = table.sequenceOf(node) <= horizon
                if (!visible || !keptBelowHorizon) {
                    if (visible) keptBelowHorizon = true
                    writer.add(
                        table.segment.readBytes(table.keyOffset(node), table.keyLength(node)),
                        table.valueOf(node)?.let { it.readBytes(0, it.byteSize().toInt()) },
                    )
                }
                node = table.nextOf(node, 0)
            }
            writer.finish(id, 0)
        }
        fsyncDirectory(Manifest.segmentsDirectory(directory))
        return SegmentFile.open(path, meta, blocks)
    }

    private fun flush(frozen: MemTable) {
        try {
            val id = lock.withLock { nextFileId++ }
            val reader = writeSegment(frozen, id, oldestVisibleSequence())

            lock.withLock {
                open += reader
                walIds.remove(frozen.walId)
                val at = generation.incrementAndGet()
                version = version.flushed(frozen, reader, at)
                retiredTables += frozen to at
                publish(currentManifest())
                flushed.signalAll()
            }
            flushCount.incrementAndGet()
            maybeCompact()
        } catch (e: Throwable) {
            // The write-ahead log still holds every one of these writes, so a failed flush costs
            // memory and a longer recovery, not data.
            logger.log(Level.SEVERE, "flush failed; the write-ahead log still holds this data", e)
        }
    }

    // Called after every flush and, crucially, after every compaction too. One flush can leave
    // level 0 over its fanout, triggering a compaction into level 1; that compaction can in turn
    // leave level 1 over its own fanout, triggering another one. The while(true) below is what
    // lets one flush cascade through as many levels as actually need draining before this
    // returns, instead of compacting one level and leaving the rest of the pile-up for the next
    // unrelated write to trip over. Each iteration re-reads version.segments under the lock,
    // so it always plans against whatever the previous iteration just published.
    private fun maybeCompact() {
        while (true) {
            val plan = lock.withLock {
                if (closed) return
                val byLevel = version.segments.groupBy { it.meta.level }
                val chosen = byLevel.entries.sortedBy { it.key }
                    .firstOrNull { (level, group) -> group.size >= config.fanout(level) } ?: return
                CompactionPlan(
                    chosen.key,
                    chosen.value.toList(),
                    // A tombstone may only be dropped when nothing deeper could still be holding
                    // the value it shadows. Drop one too early and a deleted lot comes back.
                    dropTombstones = version.segments.none { it.meta.level > chosen.key },
                )
            }
            if (!compact(plan)) return
        }
    }

    private fun compact(plan: CompactionPlan): Boolean {
        try {
            val id = lock.withLock { nextFileId++ }
            val path = Manifest.segmentPath(directory, id)
            val runs: Array<Run> = Array(plan.inputs.size) { SegmentRun(plan.inputs[it]) }
            runs.forEach { it.seek(ByteArray(0)) }

            val horizon = oldestVisibleSequence()
            val meta = SegmentFile.Writer(path, plan.inputs.sumOf { it.meta.entries }).use { writer ->
                var lastKey: ByteArray? = null
                var keptBelowHorizon = false
                while (true) {
                    val index = minimumOf(runs)
                    if (index < 0) break
                    val run = runs[index]
                    val userKey = run.userKeyBytes()
                    if (lastKey == null || !userKey.contentEquals(lastKey)) {
                        lastKey = userKey
                        keptBelowHorizon = false
                    }

                    // Same collapse as flush()
                    val visible = run.sequence() <= horizon
                    if (!visible || !keptBelowHorizon) {
                        if (visible) keptBelowHorizon = true
                        if (!(run.isDeletion() && plan.dropTombstones && visible)) {
                            writer.add(
                                run.keySegment.readBytes(run.keyOffset, run.keyLength),
                                run.value()?.let { it.readBytes(0, it.byteSize().toInt()) },
                            )
                        }
                    }
                    run.next()
                }
                writer.finish(id, plan.level + 1)
            }
            fsyncDirectory(Manifest.segmentsDirectory(directory))
            val reader = SegmentFile.open(path, meta, blocks)

            lock.withLock {
                open += reader
                val at = generation.incrementAndGet()
                version = version.compacted(plan.inputs, reader, at)
                open.removeAll(plan.inputs.toSet())
                plan.inputs.forEach { retired += it to at }
                publish(currentManifest())
            }
            compactionCount.incrementAndGet()
            return true
        } catch (e: Throwable) {
            // Nothing was published, so the previous manifest is still in force and the inputs
            // are all still live. The orphaned output gets swept on the next publish or restart.
            logger.log(Level.SEVERE, "compaction failed; the previous manifest is still in force", e)
            return false
        }
    }

    /** Frees segments no snapshot can still be reading. Called under [lock] after a manifest swap. */
    private fun collectRetired() {
        if (retired.isEmpty() && retiredTables.isEmpty()) return
        val oldest = pinnedGenerations.firstEntry()?.key ?: Long.MAX_VALUE

        val segments = retired.iterator()
        while (segments.hasNext()) {
            val (reader, at) = segments.next()
            if (oldest < at) continue
            runCatching { reader.close() }
            runCatching { Files.deleteIfExists(reader.path) }
            segments.remove()
        }

        val tables = retiredTables.iterator()
        while (tables.hasNext()) {
            val (table, at) = tables.next()
            if (oldest < at) continue
            runCatching { table.close() }
            tables.remove()
        }
    }

    /**
     * The sequence below which only the newest version of a key still matters. With no readers
     * that is everything; with one open lookup it is that lookup's own snapshot.
     */
    private fun oldestVisibleSequence(): Long = pinnedSequences.firstEntry()?.key ?: Long.MAX_VALUE

    private fun currentManifest(): Manifest = Manifest(
        lastSequence = version.durableSequence,
        nextFileId = nextFileId,
        walIds = walIds.toList(),
        segments = version.segments.map { it.meta },
    )

    private fun publish(manifest: Manifest) {
        manifest.write(directory)
        collectRetired()
        runCatching { Manifest.sweep(directory, manifest) }
    }

    internal fun releaseSnapshot(generation: Long, at: Long) {
        pinnedGenerations.computeIfPresent(generation) { _, count -> if (count.decrementAndGet() <= 0) null else count }
        pinnedSequences.computeIfPresent(at) { _, count -> if (count.decrementAndGet() <= 0) null else count }
        lock.withLock { collectRetired() }
    }

    /**
     * The runs a reader walks, newest first: the live memtables, then segments by level and
     * then by descending id.
     *
     * That order is the one invariant a point lookup leans on. The first run holding any
     * version of a key at or below the snapshot holds the newest such version. Merged scans do
     * not need it, since they resolve by sequence number outright.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun runsFor(pinned: Version): Array<Run> {
        val segments = pinned.orderedSegments
        val runs = arrayOfNulls<Run>(1 + pinned.frozen.size + segments.size)
        var at = 0
        runs[at++] = MemTableRun(pinned.active)
        for (i in pinned.frozen.indices.reversed()) runs[at++] = MemTableRun(pinned.frozen[i])
        for (segment in segments) runs[at++] = SegmentRun(segment)
        return runs as Array<Run>
    }

    /**
     * The memtables a point lookup walks, newest first. Segments are handled separately so the
     * bloom filter gets its say before anything touches a mapping.
     */
    internal fun memTablesFor(pinned: Version): List<MemTable> =
        if (pinned.frozen.isEmpty()) listOf(pinned.active) else listOf(pinned.active) + pinned.frozen.asReversed()

    internal fun segmentsFor(pinned: Version): List<SegmentReader> = pinned.orderedSegments

    private companion object {
        /**
         * Per-entry overhead the fit check has to reserve: node header, one next pointer, the
         * internal-key trailer, and alignment.
         */

        /** How long a rotation waits for the flush queue to drain before going ahead anyway. */
        const val FLUSH_WAIT_MILLIS = 2000
    }
}

private data class CompactionPlan(val level: Int, val inputs: List<SegmentReader>, val dropTombstones: Boolean)

data class LsmConfig(
    val memtableBytes: Long = 16L * 1024 * 1024,
    val maxFrozenMemtables: Int = 3,
    val sync: SyncPolicy = SyncPolicy.EveryBatch,
    val level0Fanout: Int = 4,
    val deeperFanout: Int = 6,
) {
    fun fanout(level: Int): Int = if (level == 0) level0Fanout else deeperFanout
}

class Version internal constructor(
    internal val active: MemTable,
    internal val frozen: List<MemTable>,
    internal val segments: List<SegmentReader>,
    @Volatile internal var lastSequence: Long,
    internal val durableSequence: Long,
    internal val generation: Long,
) {
    internal val orderedSegments: List<SegmentReader> =
        segments.sortedWith(compareBy({ it.meta.level }, { -it.meta.id }))

    internal fun freezing(frozenTable: MemTable, fresh: MemTable, at: Long): Version =
        Version(fresh, frozen + frozenTable, segments, lastSequence, durableSequence, at)

    internal fun flushed(flushedTable: MemTable, segment: SegmentReader, at: Long): Version =
        Version(
            active,
            frozen.filterNot { it === flushedTable },
            segments + segment,
            lastSequence,
            maxOf(durableSequence, flushedTable.maxSequence),
            at,
        )

    internal fun compacted(inputs: List<SegmentReader>, produced: SegmentReader, at: Long): Version =
        Version(
            active,
            frozen,
            segments.filterNot { reader -> inputs.any { it === reader } } + produced,
            lastSequence,
            durableSequence,
            at,
        )

    internal fun closeMemTables() {
        runCatching { active.close() }
        frozen.forEach { runCatching { it.close() } }
    }
}

private class LsmSnapshot(
    private val engine: LsmEngine,
    private val pinned: Version,
    private val at: Long,
    private val generation: Long,
) : EngineSnapshot {
    private var closed = false

    /**
     * Newest run first, and the first run holding any version of the key at or below the
     * snapshot holds the newest such version — that is the invariant the run ordering exists to
     * provide, and it is what lets this stop at the first hit instead of comparing sequence
     * numbers across every run in the store.
     *
     * The memtables are walked directly, then each segment gets a bloom probe before its
     * mapping is touched at all. Skipping that probe is what made a point lookup cost one
     * block scan per segment.
     */
    override fun get(key: ByteArray): MemorySegment? {
        val target = InternalKey.seekTarget(key)

        for (table in engine.memTablesFor(pinned)) {
            val node = table.find(key, at)
            if (node != MemTable.NOT_FOUND) return if (table.isDeletion(node)) null else table.valueOf(node)
        }

        for (segment in engine.segmentsFor(pinned)) {
            if (!segment.mightContain(key)) continue
            val run = SegmentRun(segment)
            run.seek(target, target.size)
            while (run.valid && run.userKeyLength == key.size &&
                SegmentCompare.compare(run.keySegment, run.keyOffset, key.size, key) == 0
            ) {
                if (run.sequence() <= at) {
                    return if (run.isDeletion()) null else run.value()
                }
                run.next()
            }
        }
        return null
    }

    override fun scan(prefix: ByteArray, from: ByteArray): EngineCursor {
        val runs = engine.runsFor(pinned)
        val target = InternalKey.seekTarget(from)
        runs.forEach { it.seek(target) }
        return MergingCursor(runs, prefix, at) { }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.releaseSnapshot(generation, at)
    }
}
