package com.tracel.storage.lsm

import com.tracel.engine.store.StoreSettings
import com.tracel.engine.store.StoreSync
import com.tracel.storage.lsm.segment.SegmentFile
import com.tracel.storage.lsm.write.SyncPolicy

/** The wall clock. */
val WALL_CLOCK: () -> Long = { System.currentTimeMillis() }

/**
 * How the engine sizes its memtables, when it syncs, and how it files history.
 *
 * @property classifier which family a row goes to; see [SegmentClassifier]
 * @property windowMillis how long one window of history is
 * @property windowFanout how many segments one window piles up before they are merged into one
 * @property stateBlockBytes how much a block of state holds before it is compressed and closed
 * @property historyBlockBytes the same for a block of history
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
    val stateBlockBytes: Int = SegmentFile.BLOCK_TARGET,
    val historyBlockBytes: Int = SegmentFile.BLOCK_TARGET,
) {
    /** The number of segments that can pile up at [level] before a compaction is needed. */
    fun fanout(level: Int): Int = if (level == 0) level0Fanout else deeperFanout

    /** The window [millis] falls in. */
    fun windowOf(millis: Long): Long = Math.floorDiv(millis, windowMillis)

    companion object {
        const val DAY_MILLIS: Long = 86_400_000L
    }
}

/** The engine's view of [this]: the same sizes, with the sync policy in the engine's own words. */
fun StoreSettings.toLsmConfig(): LsmConfig = LsmConfig(
    memtableBytes = memtableBytes,
    maxFrozenMemtables = maxFrozenMemtables,
    sync = when (val choice = sync) {
        StoreSync.EveryBatch -> SyncPolicy.EveryBatch
        StoreSync.Never -> SyncPolicy.Never
        is StoreSync.Interval -> SyncPolicy.Interval(choice.millis)
    },
)
