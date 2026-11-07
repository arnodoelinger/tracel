package com.tracel.storage.lsm

import com.tracel.engine.store.StoreSettings
import com.tracel.engine.store.StoreSync
import com.tracel.storage.lsm.table.SegmentClassifier
import com.tracel.storage.lsm.table.SegmentFileFormat
import com.tracel.storage.lsm.wal.WalSyncPolicy

/** The wall clock. */
val WALL_CLOCK: () -> Long = { System.currentTimeMillis() }

/** How the engine sizes its memtables, when it syncs, and how it files history. */
data class LsmOptions(
    val memtableBytes: Long = 16L * 1024 * 1024,
    val maxFrozenMemtables: Int = 3,
    val sync: WalSyncPolicy = WalSyncPolicy.EveryBatch,
    val level0Fanout: Int = 4,
    val deeperFanout: Int = 6,
    val classifier: SegmentClassifier = SegmentClassifier.NONE,
    val windowMillis: Long = DAY_MILLIS,
    val windowFanout: Int = 4,
    val clock: () -> Long = WALL_CLOCK,
    val stateBlockBytes: Int = SegmentFileFormat.BLOCK_TARGET,
    val historyBlockBytes: Int = SegmentFileFormat.BLOCK_TARGET,
    val pointRead: (keyTag: Int) -> Boolean = { true },
    val retryMillis: Long = 1000L,
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
fun StoreSettings.toLsmOptions(): LsmOptions = LsmOptions(
    memtableBytes = memtableBytes,
    maxFrozenMemtables = maxFrozenMemtables,
    sync = when (val choice = sync) {
        StoreSync.EveryBatch -> WalSyncPolicy.EveryBatch
        StoreSync.Never -> WalSyncPolicy.Never
        is StoreSync.Interval -> WalSyncPolicy.Interval(choice.millis)
    },
)
