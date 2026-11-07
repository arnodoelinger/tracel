package com.tracel.storage.lsm.table

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.LsmOptions
import com.tracel.storage.lsm.io.Disk
import com.tracel.storage.lsm.iter.AbstractRun
import com.tracel.storage.lsm.iter.minimumOf
import com.tracel.storage.lsm.memtable.MemTable
import com.tracel.storage.lsm.memtable.MemTableRun
import com.tracel.storage.lsm.version.Manifest
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/** Sorted input in, one immutable segment out. The only thing in this engine that creates a file. */
internal class SegmentMergeWriter(
    private val directory: Path,
    private val disk: Disk,
    private val blocks: BlockCache,
    private val config: LsmOptions = LsmOptions(),
) {
    /**
     * Merges [runs] into segment [id] at [level] and opens it for reading.
     *
     * [expectedEntries] is a hint for the writer's buffers, nothing more.
     */
    fun write(
        runs: Array<AbstractRun>,
        id: Long,
        level: Int,
        expectedEntries: Int,
        horizon: Long,
        dropTombstones: Boolean = false,
        category: Int = 0,
        window: Long = SegmentMetadata.NO_WINDOW,
        accept: ((AbstractRun) -> Boolean)? = null,
        replace: ((AbstractRun) -> ByteArray?)? = null,
    ): SegmentFileReader {
        val path = Manifest.segmentPath(directory, id)
        runs.forEach { it.seek(EMPTY) }

        val flush = runs.all { it is MemTableRun }
        val zstdLevel = if (flush) SegmentFileFormat.FLUSH_ZSTD_LEVEL else SegmentFileFormat.COMPACTION_ZSTD_LEVEL
        val blockBytes = if (category == 0) config.stateBlockBytes else config.historyBlockBytes
        val meta = SegmentFileBuilder(
            path, expectedEntries, zstdLevel, blockBytes, pointRead = config.pointRead, disk = disk,
        ).use { writer ->
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
                            replace?.invoke(run) ?: run.value()?.let { it.readBytes(0, it.byteSize().toInt()) },
                        )
                    }
                }
                run.next()
            }
            writer.finish(id, level, category, window)
        }

        // The segment's own bytes are already durable; this is the directory entry that names it.
        // Without it a crash can leave a manifest pointing at a file the filesystem has forgotten.
        disk.syncDirectory(Manifest.segmentsDirectory(directory))
        return SegmentFileFormat.open(path, meta, blocks, disk)
    }

    /**
     * One memtable, sealed as it stands: a segment for the state in it, and one for each kind of history, filed under
     * the window that is open right now. Flush, recovery and shutdown all mean exactly this.
     */
    fun seal(table: MemTable, nextId: () -> Long, horizon: Long): List<SegmentFileReader> {
        val counts = Int2IntOpenHashMap()
        val census = MemTableRun(table)
        census.seek(EMPTY)
        while (census.valid) {
            counts.addTo(categoryOf(census), 1)
            census.next()
        }
        if (counts.isEmpty()) return emptyList()

        val window = config.windowOf(config.clock())
        return counts.keys.toIntArray().sorted().map { category ->
            val entries = counts.get(category)
            write(
                arrayOf(MemTableRun(table)),
                nextId(),
                level = 0,
                expectedEntries = entries,
                horizon = horizon,
                category = category,
                window = if (category == 0) SegmentMetadata.NO_WINDOW else window,
                accept = { run -> categoryOf(run) == category },
            )
        }
    }

    private fun categoryOf(run: AbstractRun): Int {
        val tag = run.keySegment.get(ValueLayout.JAVA_BYTE, run.keyOffset).toInt() and 0xFF
        val value = run.value()
        val first =
            if (value == null || value.byteSize() == 0L) -1 else value.get(ValueLayout.JAVA_BYTE, 0).toInt() and 0xFF
        return config.classifier.categoryOf(tag, first)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
