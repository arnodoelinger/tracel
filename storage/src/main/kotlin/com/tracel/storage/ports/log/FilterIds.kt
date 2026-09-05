package com.tracel.storage.ports.log

import com.tracel.engine.log.LookupFilter
import com.tracel.storage.StorageUnit
import com.tracel.storage.intern.Interning

/**
 * The holders and worlds a [LookupFilter] names, resolved to interned ids once.
 *
 * Both logs open every query with the same five lines, and they were written out four times
 * between them — twice per file — with the same short-circuit and the same chance of one copy
 * drifting. Which is what happened: one of the four never checked [matchesNothing] at all.
 */
internal data class FilterIds(
    val holders: Set<Int>,
    val excluded: Set<Int>,
    val world: Int?,
    val regionWorld: Int?,
    val matchesNothing: Boolean,
)

/** Resolves [filter]'s holders and worlds against this interning table. Assigns nothing. */
internal fun Interning.idsFor(unit: StorageUnit, filter: LookupFilter): FilterIds {
    val holders = filter.holders.mapNotNull { findHolderId(unit, it) }.toSet()
    val world = filter.world?.let { findWorldId(unit, it) }
    return FilterIds(
        holders = holders,
        excluded = filter.excludedHolders.mapNotNull { findHolderId(unit, it) }.toSet(),
        world = world,
        regionWorld = filter.region?.let { findWorldId(unit, it.world) },
        matchesNothing = (filter.holders.isNotEmpty() && holders.isEmpty()) ||
                (filter.world != null && world == null),
    )
}
