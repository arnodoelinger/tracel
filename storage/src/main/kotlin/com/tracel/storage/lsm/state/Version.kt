package com.tracel.storage.lsm.state

import com.tracel.storage.lsm.read.MemTableRun
import com.tracel.storage.lsm.read.Run
import com.tracel.storage.lsm.read.SegmentRun
import com.tracel.storage.lsm.segment.SegmentReader
import com.tracel.storage.lsm.write.MemTable

/**
 * A snapshot of the LSM state at a moment in time. The active table is mutable, the frozen tables are immutable, and
 * the segments are immutable. The [lastSequence] is the last sequence number assigned to a write, and the
 * [durableSequence] is the last sequence number that has been flushed to disk.
 *
 * The [generation] is a monotonically increasing number that identifies this version.
 */
class Version internal constructor(
    internal val active: MemTable,
    internal val frozen: List<MemTable>,
    internal val segments: List<SegmentReader>,
    @Volatile internal var lastSequence: Long,
    internal val durableSequence: Long,
    internal val generation: Long,
) {
    /**
     * Newest first, so a read stops at the first segment that answers.
     *
     * Shallower level first, and within a level the higher file id: a segment written later
     * shadows what it was merged from.
     */
    internal val orderedSegments: List<SegmentReader> =
        segments.sortedWith(compareBy({ it.meta.level }, { -it.meta.id }))

    /** The active table becomes frozen and a fresh one takes over. Nothing has reached disk yet! */
    internal fun freezing(frozenTable: MemTable, fresh: MemTable, at: Long): Version =
        Version(fresh, frozen + frozenTable, segments, lastSequence, durableSequence, at)

    /**
     * A frozen table is now a segment on disk.
     *
     * [durableSequence] only moves here, because this is the moment those writes stop depending
     * on the write-ahead log to survive.
     */
    internal fun flushed(flushedTable: MemTable, segment: SegmentReader, at: Long): Version {
        val left = frozen.filterNot { it === flushedTable }
        val oldestLeft = left.filter { it.entries > 0 }.minOfOrNull { it.minSequence }
        val durable = when {
            oldestLeft != null -> oldestLeft - 1
            active.entries > 0 -> active.minSequence - 1
            else -> lastSequence
        }
        return Version(active, left, segments + segment, lastSequence, maxOf(durableSequence, durable), at)
    }

    /** Several segments became one. Identity comparison: two segments can carry equal metadata. */
    internal fun compacted(inputs: List<SegmentReader>, produced: SegmentReader, at: Long): Version =
        Version(
            active,
            frozen,
            segments.filterNot { reader -> inputs.any { it === reader } } + produced,
            lastSequence,
            durableSequence,
            at,
        )

    /** Closes the active and frozen tables. The segments are immutable and do not need closing. */
    internal fun closeMemTables() {
        runCatching { active.close() }
        frozen.forEach { runCatching { it.close() } }
    }

    /**
     * Every source a read has to merge, newest first.
     *
     * The active table, then the frozen ones in reverse order of freezing, then the segments. A
     * key present in two of them is the same key at two ages, and the first hit is the newest.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun runs(): Array<Run> {
        val ordered = orderedSegments
        val runs = arrayOfNulls<Run>(1 + frozen.size + ordered.size)
        var at = 0
        runs[at++] = MemTableRun(active)
        for (i in frozen.indices.reversed()) runs[at++] = MemTableRun(frozen[i])
        for (segment in ordered) runs[at++] = SegmentRun(segment)
        return runs as Array<Run>
    }

    /** The skip lists alone, in the same order. A point read tries these before touching a file. */
    internal fun memTables(): List<MemTable> =
        if (frozen.isEmpty()) listOf(active) else listOf(active) + frozen.asReversed()
}
