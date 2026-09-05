package com.tracel.storage.lsm.state

import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.segment.SegmentReader
import com.tracel.storage.lsm.segment.SegmentWriter
import com.tracel.storage.lsm.write.MemTable
import com.tracel.storage.lsm.write.Wal
import java.nio.file.Path

/** What replaying the write-ahead logs left behind. */
internal class Recovered(
    val active: MemTable,
    val sealed: List<SegmentReader>,
    val lastSequence: Long,
    val nextFileId: Long,
)

/**
 * Rebuilds what was in memory when the process died.
 *
 * It's used at startup to recover the last state of the database from the write-ahead logs,
 * and it can also be used in tests to simulate a crash and recovery.
 *
 * @return a [Recovered] object containing the active memtable, sealed segments, last sequence number, and next file ID.
 */
internal fun replayWals(
    directory: Path,
    manifest: Manifest,
    config: LsmConfig,
    writer: SegmentWriter,
): Recovered {
    var nextFileId = manifest.nextFileId
    var recovered = manifest.lastSequence
    var active = MemTable(config.memtableBytes)
    val sealed = ArrayList<SegmentReader>()

    /** Seals a full memtable to a segment and closes it. */
    fun seal(table: MemTable) {
        sealed += writer.seal(table, nextFileId++, horizon = Long.MAX_VALUE)
        table.close()
    }

    // Replay every log in order, and if a memtable fills up, seal it to a segment and start a new one
    for (id in manifest.walIds) {
        val applied = Wal.replay(Manifest.walPath(directory, id), manifest.lastSequence) { seq, key, value ->
            if (!active.put(key, value, seq)) {
                seal(active)
                active = MemTable(maxOf(config.memtableBytes, entryBytes(key, value) * 2))
                check(active.put(key, value, seq)) {
                    "a recovered entry does not fit a memtable sized for it — the accounting is wrong"
                }
            }
        }
        if (applied > recovered) recovered = applied
    }
    if (active.entries > 0) {
        seal(active)
        active = MemTable(config.memtableBytes)
    }

    return Recovered(active, sealed, recovered, nextFileId)
}

internal fun entryBytes(key: ByteArray, value: ByteArray?): Long =
    key.size + (value?.size ?: 0) + MemTable.MAX_ENTRY_OVERHEAD
