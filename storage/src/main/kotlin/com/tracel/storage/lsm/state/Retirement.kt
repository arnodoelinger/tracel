package com.tracel.storage.lsm.state

import com.tracel.platform.io.closeQuietly
import com.tracel.storage.lsm.disk.Disk
import com.tracel.storage.lsm.segment.SegmentReader
import com.tracel.storage.lsm.write.MemTable
import it.unimi.dsi.fastutil.longs.Long2IntRBTreeMap
import java.util.*

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
internal class Retirement(private val disk: Disk) {
    private val pins = Any()
    private val generations = Long2IntRBTreeMap()
    private val sequences = Long2IntRBTreeMap()

    private val segments = ArrayList<Pair<SegmentReader, Long>>()
    private val tables = ArrayList<Pair<MemTable, Long>>()

    /** Records a reader taking hold of [generation] as of sequence [at]. */
    fun pin(generation: Long, at: Long) = synchronized(pins) {
        generations.put(generation, generations.get(generation) + 1)
        sequences.put(at, sequences.get(at) + 1)
    }

    /** The mirror of [pin]. The caller sweeps afterwards, under the engine's lock. */
    fun release(generation: Long, at: Long) = synchronized(pins) {
        unpin(generations, generation)
        unpin(sequences, at)
    }

    /** The oldest sequence any open reader can still see — the horizon a merge collapses to. */
    fun horizon(): Long = synchronized(pins) { if (sequences.isEmpty()) Long.MAX_VALUE else sequences.firstLongKey() }

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
     *
     * A segment also waits for [published] to reach `at`, so no manifest on disk still names it.
     */
    fun sweep(published: Long) {
        if (segments.isEmpty() && tables.isEmpty()) return
        val oldest = oldestPinned()

        val files = segments.iterator()
        while (files.hasNext()) {
            val (reader, at) = files.next()
            if (oldest < at || published < at) continue
            reader.closeQuietly()
            runCatching { disk.delete(reader.path) }
            files.remove()
        }

        val skipLists = tables.iterator()
        while (skipLists.hasNext()) {
            val (table, at) = skipLists.next()
            if (oldest < at) continue
            table.closeQuietly()
            skipLists.remove()
        }
    }

    /** Lets go of everything, readers or not. Shutdown, when nobody is left to be reading. */
    fun closeAll() {
        segments.forEach { (reader, _) -> reader.closeQuietly() }
        tables.forEach { (table, _) -> table.closeQuietly() }
        segments.clear()
        tables.clear()
    }

    private fun oldestPinned(): Long =
        synchronized(pins) { if (generations.isEmpty()) Long.MAX_VALUE else generations.firstLongKey() }

    private fun unpin(counts: Long2IntRBTreeMap, key: Long) {
        val count = counts.get(key)
        if (count == 1) counts.remove(key) else if (count > 1) counts.put(key, count - 1)
    }
}
