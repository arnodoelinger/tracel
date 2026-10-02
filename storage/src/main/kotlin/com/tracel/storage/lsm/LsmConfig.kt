package com.tracel.storage.lsm

import com.tracel.storage.lsm.write.SyncPolicy

/** The wall clock. */
val WALL_CLOCK: () -> Long = { System.currentTimeMillis() }

/**
 * How the engine sizes its memtables, when it syncs, and how it files history.
 *
 * @property classifier which family a row goes to; see [SegmentClassifier]
 * @property windowMillis how long one window of history is
 * @property windowFanout how many segments one window piles up before they are merged into one
 */
data class LsmConfig(
    val memtableBytes: Long = 16L * 1024 * 1024,
    val maxFrozenMemtables: Int = 3,
    val sync: SyncPolicy = SyncPolicy.EveryBatch,
    val level0Fanout: Int = 4,
    val deeperFanout: Int = 6,
    val classifier: SegmentClassifier = SegmentClassifier.NONE,
    val windowMillis: Long = DAY_MILLIS,
    val windowFanout: Int = 4,
    val clock: () -> Long = WALL_CLOCK,
) {
    /** The number of segments that can pile up at [level] before a compaction is needed. */
    fun fanout(level: Int): Int = if (level == 0) level0Fanout else deeperFanout

    /** The window [millis] falls in. */
    fun windowOf(millis: Long): Long = Math.floorDiv(millis, windowMillis)

    companion object {
        const val MIN_MEMTABLE_BYTES: Long = 1L shl 20
        const val MIN_PENDING_FLUSHES: Int = 1
        const val DAY_MILLIS: Long = 86_400_000L
    }
}
