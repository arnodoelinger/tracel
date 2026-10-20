package com.tracel.api.admin

import com.tracel.api.Unstable
import com.tracel.api.result.Res
import java.time.Instant

/** A foreign history that can be moved over, found on this server. */
@Unstable
public interface MigrationSource {
    /** Where it lives, as the admin should read it. */
    override fun toString(): String
}

/** What moving a [MigrationSource] over would do, without doing it. */
@Unstable
public data class MigrationOutlook(
    /** Rows still to read. */
    public val rowsLeft: Long,

    /** The span the source covers, when it says. */
    public val span: Pair<Instant, Instant>?,
)

/** How a migration ended. */
@Unstable
public data class MigrationOutcome(
    public val stopped: Boolean,
    public val tookMillis: Long,
    public val rows: Long,
    public val taken: Long,
    public val skipped: Long,
    public val oldest: Instant?,
    public val newest: Instant?,
)

/**
 * Moving what `CoreProtect` recorded under what is recorded here.
 *
 * Nothing is replaced, and running twice takes nothing twice.
 */
@Unstable
public interface Migration {
    /** The `CoreProtect` database on this server, or [AdminError.NoSource]. */
    public fun locate(): Res<MigrationSource, AdminError>

    /** What [source] holds that has not been moved yet. */
    public suspend fun outlook(source: MigrationSource): Res<MigrationOutlook, AdminError>

    /**
     * Moves what is left of [source], a batch at a time; what is taken stays,
     * and the next run carries on.
     */
    public suspend fun run(
        source: MigrationSource,
        stop: () -> Boolean,
        onStart: () -> Unit = {},
        onWait: () -> Unit = {},
        progress: (done: Long, of: Long) -> Unit = { _, _ -> },
    ): Res<MigrationOutcome, AdminError>
}
