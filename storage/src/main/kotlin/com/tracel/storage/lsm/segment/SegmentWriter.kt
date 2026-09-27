package com.tracel.storage.lsm.segment

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.fsyncDirectory
import com.tracel.storage.lsm.read.MemTableRun
import com.tracel.storage.lsm.read.Run
import com.tracel.storage.lsm.read.minimumOf
import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.lsm.write.MemTable
import java.nio.file.Path

/** Sorted input in, one immutable segment out. The only thing in this engine that creates a file. */
internal class SegmentWriter(
    private val directory: Path,
    private val blocks: BlockCache,
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
                    if (!(dropTombstones && visible && run.isDeletion())) {
                        writer.add(
                            run.keySegment.readBytes(run.keyOffset, run.keyLength),
                            run.value()?.let { it.readBytes(0, it.byteSize().toInt()) },
                        )
                    }
                }
                run.next()
            }
            writer.finish(id, level)
        }

        // The segment's own bytes are already durable; this is the directory entry that names it.
        // Without it a crash can leave a manifest pointing at a file the filesystem has forgotten.
        fsyncDirectory(Manifest.segmentsDirectory(directory))
        return SegmentFile.open(path, meta, blocks)
    }

    /** One memtable, sealed as it stands. Flush, recovery and shutdown all mean exactly this. */
    fun seal(table: MemTable, id: Long, horizon: Long): SegmentReader =
        write(arrayOf(MemTableRun(table)), id, level = 0, expectedEntries = table.entries, horizon = horizon)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
