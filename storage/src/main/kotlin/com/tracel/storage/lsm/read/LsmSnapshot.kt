package com.tracel.storage.lsm.read

import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.InternalKey
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.state.Retirement
import com.tracel.storage.lsm.state.Version
import com.tracel.storage.lsm.write.MemTable
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.EngineSnapshot
import java.lang.foreign.MemorySegment

/**
 * One consistent view of the store, for as long as somebody holds it.
 *
 * The read path, and it takes no lock at all. Everything it needs is the [Version] it pinned.
 *
 * @see Retirement
 */
internal class LsmSnapshot(
    private val engine: LsmEngine,
    private val pinned: Version,
    private val at: Long,
    private val generation: Long,
) : EngineSnapshot {
    private var closed = false

    override fun get(key: ByteArray): MemorySegment? {
        // Skip lists first: they hold the newest writes, and answering from one costs no file
        // access at all.
        for (table in pinned.memTables()) {
            val node = table.find(key, at)
            if (node != MemTable.NOT_FOUND) return if (table.isDeletion(node)) null else table.valueOf(node)
        }

        val target = InternalKey.seekTarget(key)
        for (segment in pinned.orderedSegments) {
            if (!segment.inRange(key) || !segment.mightContain(key)) continue
            val run = SegmentRun(segment)
            run.seek(target, target.size)
            // Versions of one key sit together, newest first. Walk past the ones this reader is
            // too old to see, and stop at the first it can.
            while (run.valid && run.userKeyLength == key.size &&
                SegmentCompare.compare(run.keySegment, run.keyOffset, key.size, key) == 0
            ) {
                if (run.sequence() <= at) return if (run.isDeletion()) null else run.value()
                run.next()
            }
        }
        return null
    }

    override fun scan(prefix: ByteArray, from: ByteArray): EngineCursor {
        val runs = pinned.runs(from, prefix)
        val target = InternalKey.seekTarget(from)
        runs.forEach { it.seek(target) }
        return MergingCursor(runs, prefix, at) { }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.releaseSnapshot(generation, at)
    }

    /** Only segment [id] of what this snapshot pinned, from [prefix] on; `null` if it was never part of it. */
    internal fun scanSegment(id: Long, prefix: ByteArray): EngineCursor? {
        val reader = pinned.segments.firstOrNull { it.meta.id == id } ?: return null
        val run = SegmentRun(reader)
        run.seek(InternalKey.seekTarget(prefix))
        return MergingCursor(arrayOf(run), prefix, at) { }
    }
}
