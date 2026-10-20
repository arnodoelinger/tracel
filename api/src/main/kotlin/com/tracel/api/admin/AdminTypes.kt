package com.tracel.api.admin

import com.tracel.api.Unstable
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * A kind of history that a purge may take.
 *
 * Everything else, the item ledger included, is state and stays.
 */
@Unstable
public enum class PurgeCategory {
    /** Block and entity changes in the world. */
    BLOCKS,

    /** Item movements. */
    ITEMS,

    /** Container layouts over time, and the visits that opened them. */
    CONTAINERS,

    /** What was said and typed, and who joined and disconnected. */
    EVENTS,
}

/**
 * What to purge: [categories], narrowed by whatever of [before], [world] and [player] is set.
 *
 * A record that misses any of it stays.
 */
@Unstable
public data class PurgeRequest(
    public val categories: Set<PurgeCategory> = PurgeCategory.entries.toSet(),
    public val before: Instant? = null,
    public val world: UUID? = null,
    public val player: UUID? = null,
) {
    init {
        require(categories.isNotEmpty()) { "A purge of no category purges nothing." }
        require(PurgeCategory.CONTAINERS !in categories || player == null) {
            "A container layout is nobody's doing, so it cannot be purged by player."
        }
    }
}

/**
 * One category's share of a purge: [matched] of its [total] records,
 * in [rows] rows that took [bytes] of disk.
 */
@Unstable
public data class PurgeTally(
    public val total: Long,
    public val matched: Long,
    public val rows: Long,
    public val bytes: Long,
)

/** What a partial purge took, or would take. */
@Unstable
public data class PurgeReport(
    public val tallies: Map<PurgeCategory, PurgeTally>,
    public val oldest: Instant?,
    public val newest: Instant?,
) {
    /** Records matched, all categories. */
    public val matched: Long get() = tallies.values.sumOf { it.matched }

    /** Rows taken, indexes included. */
    public val rows: Long get() = tallies.values.sumOf { it.rows }

    /** Disk freed. */
    public val bytes: Long get() = tallies.values.sumOf { it.bytes }
}

/** What a purge of everything threw away. */
@Unstable
public data class PurgeSummary(
    public val rows: Long,
    public val bytes: Long,
    public val oldest: Instant?,
    public val newest: Instant?,
)

/** A snapshot file in the export directory. */
@Unstable
public data class Snapshot(public val name: String, public val file: Path, public val bytes: Long)

/** What an export wrote, or an import read. */
@Unstable
public data class TransferSummary(
    public val rows: Long,
    public val bytes: Long,
    public val file: Path,
    public val oldest: Instant?,
    public val newest: Instant?,
)

/** Asked while a snapshot is imported. */
@Unstable
public interface ImportControl {
    /**
     * Whether the admin asked to stop.
     *
     * Asked while the file is still being checked.
     */
    public fun stopRequested(): Boolean

    /**
     * Called just before the old history is replaced. `false` means it is too late for
     * the import to go on, or it was stopped in the meantime.
     */
    public fun commit(): Boolean
}
