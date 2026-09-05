package com.tracel.storage.lsm.state

import com.tracel.storage.lsm.segment.SegmentReader
import com.tracel.storage.lsm.write.MemTable
import java.nio.file.Files
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * When a segment file may actually be unmapped and deleted, and when an old version of a key may
 * be forgotten.
 *
 * The subtlest thing in the engine, and the reason readers need no lock. A compaction publishes a
 * new [Version] the instant it finishes, but a reader that pinned the old one is still walking
 * the segments that merge replaced.
 *
 * So nothing is deleted on publish.
 */
internal class Retirement {
    private val generations = ConcurrentSkipListMap<Long, AtomicInteger>()
    private val sequences = ConcurrentSkipListMap<Long, AtomicInteger>()

    private val segments = ArrayList<Pair<SegmentReader, Long>>()
    private val tables = ArrayList<Pair<MemTable, Long>>()

    /** Records a reader taking hold of [generation] as of sequence [at]. */
    fun pin(generation: Long, at: Long) {
        generations.computeIfAbsent(generation) { AtomicInteger(0) }.incrementAndGet()
        sequences.computeIfAbsent(at) { AtomicInteger(0) }.incrementAndGet()
    }

    /** The mirror of [pin]. The caller sweeps afterwards, under the engine's lock. */
    fun release(generation: Long, at: Long) {
        generations.computeIfPresent(generation) { _, count -> if (count.decrementAndGet() <= 0) null else count }
        sequences.computeIfPresent(at) { _, count -> if (count.decrementAndGet() <= 0) null else count }
    }

    /** The oldest sequence any open reader can still see — the horizon a merge collapses to. */
    fun horizon(): Long = sequences.firstEntry()?.key ?: Long.MAX_VALUE

    /** Hands [segment] over, to be unmapped and deleted once nobody is reading generation [at]. */
    fun retire(segment: SegmentReader, at: Long) {
        segments += segment to at
    }

    /** The same for a skip list, which costs off-heap memory rather than a file. */
    fun retire(table: MemTable, at: Long) {
        tables += table to at
    }

    /**
     * Closes and deletes everything no reader can still reach. Call under the engine's lock.
     *
     * An entry retired at generation `at` is safe once the oldest pin is `at` or newer: every
     * reader older than that has closed, and no new reader can pin a generation that is gone.
     */
    fun sweep() {
        if (segments.isEmpty() && tables.isEmpty()) return
        val oldest = generations.firstEntry()?.key ?: Long.MAX_VALUE

        val files = segments.iterator()
        while (files.hasNext()) {
            val (reader, at) = files.next()
            if (oldest < at) continue
            runCatching { reader.close() }
            runCatching { Files.deleteIfExists(reader.path) }
            files.remove()
        }

        val skipLists = tables.iterator()
        while (skipLists.hasNext()) {
            val (table, at) = skipLists.next()
            if (oldest < at) continue
            runCatching { table.close() }
            skipLists.remove()
        }
    }

    /** Lets go of everything, readers or not. Shutdown, when nobody is left to be reading. */
    fun closeAll() {
        segments.forEach { (reader, _) -> runCatching { reader.close() } }
        tables.forEach { (table, _) -> runCatching { table.close() } }
        segments.clear()
        tables.clear()
    }
}
