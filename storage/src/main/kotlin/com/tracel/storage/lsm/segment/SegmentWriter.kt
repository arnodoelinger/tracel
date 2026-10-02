package com.tracel.storage.lsm.segment

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.fsyncDirectory
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.read.MemTableRun
import com.tracel.storage.lsm.read.Run
import com.tracel.storage.lsm.read.minimumOf
import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.lsm.write.MemTable
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/** Sorted input in, one immutable segment out. The only thing in this engine that creates a file. */
internal class SegmentWriter(
    private val directory: Path,
    private val blocks: BlockCache,
    private val config: LsmConfig = LsmConfig(),
) {
    /**
     * Merges [runs] into segment [id] at [level] and opens it for reading.
     *
     * [expectedEntries] only sizes the writer's filter up front; being wrong costs a little space,
     * not correctness.
     */
    fun write(
        runs: Array<Run>,
        id: Long,
        level: Int,
        expectedEntries: Int,
        horizon: Long,
        dropTombstones: Boolean = false,
        category: Int = 0,
        window: Long = SegmentMeta.NO_WINDOW,
        accept: ((Run) -> Boolean)? = null,
    ): SegmentReader {
        val path = Manifest.segmentPath(directory, id)
        runs.forEach { it.seek(EMPTY) }

        val meta = SegmentFile.Writer(path, expectedEntries).use { writer ->
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

                val visible = run.sequence() <= horizon
                if (!visible || !keptBelowHorizon) {
                    if (visible) keptBelowHorizon = true
                    if (!(dropTombstones && visible && run.isDeletion()) && (accept == null || accept(run))) {
                        writer.add(
                            run.keySegment.readBytes(run.keyOffset, run.keyLength),
                            run.value()?.let { it.readBytes(0, it.byteSize().toInt()) },
                        )
                    }
                }
                run.next()
            }
            writer.finish(id, level, category, window)
        }

        // The segment's own bytes are already durable; this is the directory entry that names it.
        // Without it a crash can leave a manifest pointing at a file the filesystem has forgotten.
        fsyncDirectory(Manifest.segmentsDirectory(directory))
        return SegmentFile.open(path, meta, blocks)
    }

    /**
     * One memtable, sealed as it stands: a segment for the state in it, and one for each kind of history, filed under
     * the window that is open right now. Flush, recovery and shutdown all mean exactly this.
     */
    fun seal(table: MemTable, nextId: () -> Long, horizon: Long): List<SegmentReader> {
        val counts = HashMap<Int, Int>()
        val census = MemTableRun(table)
        census.seek(EMPTY)
        while (census.valid) {
            counts.merge(categoryOf(census), 1, Int::plus)
            census.next()
        }
        if (counts.isEmpty()) return emptyList()

        val window = config.windowOf(config.clock())
        return counts.entries.sortedBy { it.key }.map { (category, entries) ->
            write(
                arrayOf(MemTableRun(table)),
                nextId(),
                level = 0,
                expectedEntries = entries,
                horizon = horizon,
                category = category,
                window = if (category == 0) SegmentMeta.NO_WINDOW else window,
                accept = { run -> categoryOf(run) == category },
            )
        }
    }

    private fun categoryOf(run: Run): Int {
        val tag = run.keySegment.get(ValueLayout.JAVA_BYTE, run.keyOffset).toInt() and 0xFF
        val value = run.value()
        val first = if (value == null || value.byteSize() == 0L) -1 else value.get(ValueLayout.JAVA_BYTE, 0).toInt() and 0xFF
        return config.classifier.categoryOf(tag, first)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
