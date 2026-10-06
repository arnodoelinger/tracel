package com.tracel.engine.store

import com.tracel.model.world.WorldId
import java.util.*

/** What a partial purge may take. Everything else, the lot ledger included, is state and stays. */
public enum class PurgeCategory {
    /** Block and entity changes in the world. */
    BLOCKS,

    /** Item movements: the transactions and every index on them. */
    ITEMS,

    /** Container layouts over time, and the visits that opened them. */
    CONTAINERS,

    /** What was said and typed, and who joined and disconnected. */
    EVENTS,
}

/**
 * What to take. Whatever is set has to match; a record that misses any of it stays.
 *
 * @property before only records older than this, in epoch millis
 * @property world only what happened in this world. Container visits have no world and stay
 * @property player only what this player did
 */
public data class PurgeFilter(
    public val before: Long? = null,
    public val world: WorldId? = null,
    public val player: UUID? = null,
)

/** A [filter] applied to [categories]. */
public data class PurgeSpec(
    public val categories: Set<PurgeCategory>,
    public val filter: PurgeFilter = PurgeFilter(),
    public val wholeWindowsOnly: Boolean = false,
) {
    init {
        require(!wholeWindowsOnly || (filter.world == null && filter.player == null)) {
            "a window can only be left on the edge when the age is all that is asked"
        }
        require(categories.isNotEmpty()) { "a purge of no category purges nothing" }
        require(PurgeCategory.CONTAINERS !in categories || filter.player == null) {
            "a container layout is nobody's doing, so it cannot be purged by player"
        }
    }
}

/**
 * One category's share: [matched] of its [total] records, [rows] rows with the indexes, the disk [bytes] they took,
 * and the span they cover.
 */
public data class PurgeTally(
    public val total: Long,
    public val matched: Long,
    public val rows: Long,
    public val oldest: Long?,
    public val newest: Long?,
    public val bytes: Long = 0,
)

/** What a partial purge took, or would take. */
public data class PurgeReport(public val tallies: Map<PurgeCategory, PurgeTally>) {
    public val matched: Long get() = tallies.values.sumOf { it.matched }
    public val rows: Long get() = tallies.values.sumOf { it.rows }
    public val bytes: Long get() = tallies.values.sumOf { it.bytes }
    public val oldest: Long? get() = tallies.values.mapNotNull { it.oldest }.minOrNull()
    public val newest: Long? get() = tallies.values.mapNotNull { it.newest }.maxOrNull()
}

/** What a purge of everything threw away, for the line that gets printed afterward. */
public data class PurgeSummary(
    public val rows: Long,
    public val bytes: Long,
    public val oldest: Long? = null,
    public val newest: Long? = null,
)

/** Runs one piece of work; whoever hands one in may make it wait for a good moment first. */
public typealias Around = suspend (suspend () -> Unit) -> Unit
