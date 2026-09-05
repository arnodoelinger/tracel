package com.tracel.storage.lsm.segment

import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.state.Version

/**
 * Segments to merge into one, and whether this merge is allowed to forget deletions.
 *
 * @param level the level to merge into
 * @param inputs the segments to merge, all at [level]
 * @param dropTombstones whether this merge is allowed to forget deletions
 */
internal data class CompactionPlan(
    val level: Int,
    val inputs: List<SegmentReader>,
    val dropTombstones: Boolean,
) {
    val expectedEntries: Int get() = inputs.sumOf { it.meta.entries }
}

/**
 * The shallowest level that has piled up past its fanout, or null when nothing needs merging.
 *
 * Called after every flush and after every compaction. One flush can leave level 0 over its
 * fanout, that merge can leave level 1 over its own, and so on down. Planning again after each
 * one is what lets a single flush cascade as far as it actually has to, instead of draining one
 * level and leaving the rest for whatever unrelated write trips over it next.
 *
 * @param version the current state of the store
 * @param config the store's configuration, which decides how many segments are too many
 *
 * @return a plan for the next compaction, or null if nothing needs merging
 */
internal fun planCompaction(version: Version, config: LsmConfig): CompactionPlan? {
    val byLevel = version.segments.groupBy { it.meta.level }
    val chosen = byLevel.entries.sortedBy { it.key }
        .firstOrNull { (level, group) -> group.size >= config.fanout(level) } ?: return null
    return CompactionPlan(
        chosen.key,
        chosen.value.toList(),
        dropTombstones = version.segments.none { it.meta.level > chosen.key },
    )
}
