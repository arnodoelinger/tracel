package com.tracel.engine.store

import java.nio.file.Path

/**
 * What an operator can do to the stored history besides reading and appending it: look at it, shrink it,
 * move it in and out, and shut it down.
 */
public interface StoreAdmin {
    /** How big the history is and how old, for the status line and for telemetry. Reads disk metadata: not on a server thread. */
    public fun report(): StoreReport

    /** How the capture queue is doing. */
    public val capture: CaptureHealth

    /** What the database takes on disk right now. */
    public val liveBytes: Long

    /** The format version the database is in, as shown to people. */
    public val formatVersion: String

    /** Counts what a purge of [spec] would take, and touches nothing. */
    public suspend fun previewPurge(spec: PurgeSpec): PurgeReport

    /**
     * Takes what [spec] says out of the history. Every piece goes through [around], where a caller makes it
     * wait out whatever must not run beside it.
     */
    public suspend fun purgeSome(spec: PurgeSpec, around: Around = { it() }): PurgeReport

    /** Deletes the whole history. */
    public suspend fun purgeAll(): PurgeSummary

    /** Writes the whole history to [to]. */
    public suspend fun exportTo(to: Path, stopped: () -> Boolean = { false }): ExportSummary

    /**
     * Replaces the history with what [from] holds. Destructive: the store is wiped first. [commit] is asked once the
     * file has been checked, and says whether to go on.
     */
    public suspend fun importFrom(
        from: Path,
        stopped: () -> Boolean = { false },
        commit: () -> Boolean = { true },
    ): ExportSummary

    /**
     * Runs [last] to its end or for [timeoutMillis], whichever comes first, then closes. Blocks the caller, so
     * shutdown only.
     *
     * @return whether [last] finished in time.
     */
    public fun closeAfter(timeoutMillis: Long, last: suspend () -> Unit): Boolean
}

/**
 * How much history there is, by kind.
 *
 * @property oldestMillis when the oldest record of any kind happened, or `null` if there is none
 * @property liveBytes what the database takes on disk
 */
public data class StoreReport(
    public val blockRows: Long,
    public val itemRows: Long,
    public val eventRows: Long,
    public val containerRows: Long,
    public val oldestMillis: Long?,
    public val liveBytes: Long,
) {
    public val rows: Long get() = blockRows + itemRows + eventRows + containerRows
}

/** The capture queue between the server threads and the storage thread. */
public interface CaptureHealth {
    /** Events lost since start, because the queue and its waiting room were both full. */
    public val dropped: Long

    /** How many times the queue itself was full. */
    public val ringFull: Long

    /** Events captured and not yet written. */
    public val backlog: Long

    /** Waits until everything captured so far is written, or [timeoutMs] runs out. */
    public suspend fun awaitApplied(timeoutMs: Long): Boolean
}
