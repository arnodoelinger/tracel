package com.tracel.storage.lsm

import com.tracel.storage.lsm.write.SyncPolicy

/**
 * `Tracel` LSM configuration.
 *
 * @param memtableBytes the maximum size of a memtable before it is frozen and flushed to disk
 * @param maxFrozenMemtables the maximum number of frozen memtables before writes are blocked
 * @param sync the policy for syncing writes to disk
 * @param level0Fanout the number of segments that can pile up at level 0 before a compaction is needed
 * @param deeperFanout the number of segments that can pile up at levels 1 and deeper before a compaction is needed
 */
data class LsmConfig(
    val memtableBytes: Long = 16L * 1024 * 1024,
    val maxFrozenMemtables: Int = 3,
    val sync: SyncPolicy = SyncPolicy.EveryBatch,
    val level0Fanout: Int = 4,
    val deeperFanout: Int = 6,
) {
    /** The number of segments that can pile up at [level] before a compaction is needed. */
    fun fanout(level: Int): Int = if (level == 0) level0Fanout else deeperFanout

    companion object {
        const val MIN_MEMTABLE_BYTES: Long = 1L shl 20
        const val MIN_PENDING_FLUSHES: Int = 1
    }
}
