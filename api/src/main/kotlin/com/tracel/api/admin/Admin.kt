package com.tracel.api.admin

import com.tracel.api.Unstable
import com.tracel.api.result.Res
import java.nio.file.Path

/**
 * What only an admin does to the stored history: purge, export and import snapshots,
 * move another plugin's history over, and look at how the store is doing.
 *
 * Purging and importing cannot be undone, so none of it is a grant: it exists only while
 * the admin allows destructive operations in `config.toml`, for every plugin at once, and
 * it is off by default:
 *
 * ```
 * [api]
 * allow-destructive = true
 * ```
 */
@Unstable
public interface Admin {
    /** Where snapshots are written and read. */
    public val exportDirectory: Path

    /** The disk the history takes right now. */
    public val storeBytes: Long

    /** How the store is doing. A late capture queue is waited out for a few seconds so its lag can be measured. */
    public suspend fun status(): Res<StoreStatus, AdminError>

    /** What [request] would take, once the last captures are on disk. Takes nothing. */
    public suspend fun previewPurge(request: PurgeRequest): Res<PurgeReport, AdminError>

    /**
     * Takes what [request] names, a slice at a time between rollbacks.
     *
     * [onStart] is called once the purge holds its place, and [onWait] if it must wait for a rollback to end.
     */
    public suspend fun purge(
        request: PurgeRequest,
        onStart: () -> Unit = {},
        onWait: () -> Unit = {},
    ): Res<PurgeReport, AdminError>

    /** Takes all of it, once no rollback or undo is running. [onWait] is called once if one is. */
    public suspend fun purgeEverything(onWait: () -> Unit = {}): Res<PurgeSummary, AdminError>

    /** Writes a snapshot of the history to the export directory; [stopped] is asked while the file is checked. */
    public suspend fun export(stopped: () -> Boolean = { false }): Res<TransferSummary, AdminError>

    /** The snapshot called [name] in the export directory. */
    public suspend fun snapshot(name: String): Res<Snapshot, AdminError>

    /**
     * Replaces the history with snapshot [name], and forgets what caches remember of the old one.
     *
     * [onStart] is called once the import holds its place.
     *
     * Nothing else may purge, import or roll back meanwhile.
     */
    public suspend fun importSnapshot(
        name: String,
        control: ImportControl,
        onStart: () -> Unit = {},
    ): Res<TransferSummary, AdminError>

    /** Moving another plugin's history over. */
    public val migration: Migration
}
