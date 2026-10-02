package com.tracel.storage.lsm.segment

import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.state.Version

/**
 * Segments to merge into one, and whether this merge is allowed to forget deletions.
 *
 * @param level the level to merge into, less one; `-1` for a window of history, which has no levels
 * @param inputs the segments to merge
 * @param dropTombstones whether this merge is allowed to forget deletions
 * @param category the family the output belongs to, `0` for state
 * @param window the window the output belongs to, for history
 */
internal data class CompactionPlan(
    val level: Int,
    val inputs: List<SegmentReader>,
    val dropTombstones: Boolean,
    val category: Int = 0,
    val window: Long = SegmentMeta.NO_WINDOW,
) {
    val expectedEntries: Int get() = inputs.sumOf { it.meta.entries }
}

/**
 * The next merge worth doing, or null when nothing needs one.
 *
 * Called after every flush and after every compaction. One flush can leave level 0 over its fanout, that merge can
 * leave level 1 over its own, and so on down, so planning again after each one lets a single flush cascade as far
 * as it actually has to.
 *
 * @param version the current state of the store
 * @param config the store's configuration, which decides how many segments are too many
 * @param openWindow the window that is still being written
 */
internal fun planCompaction(version: Version, config: LsmConfig, openWindow: Long): CompactionPlan? {
    val state = version.segments.filter { !it.meta.isHistory }
    val chosen = state.groupBy { it.meta.level }.entries.sortedBy { it.key }
        .firstOrNull { (level, group) -> group.size >= config.fanout(level) }
    if (chosen != null) {
        return CompactionPlan(
            chosen.key,
            chosen.value.toList(),
            dropTombstones = state.none { it.meta.level > chosen.key },
        )
    }

    val pending = version.segments.filter { it.meta.isHistory }
        .groupBy { it.meta.category to it.meta.window }.entries
        .filter { (key, group) -> group.size >= config.windowFanout || (key.second < openWindow && group.size > 1) }
        .minByOrNull { it.key.second } ?: return null
    return CompactionPlan(-1, pending.value.toList(), dropTombstones = false, pending.key.first, pending.key.second)
}
