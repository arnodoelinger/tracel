package com.tracel.plugin.api.admin

import com.tracel.api.admin.Admin
import com.tracel.api.admin.AdminError
import com.tracel.api.admin.AutoPurgeStatus
import com.tracel.api.admin.ImportControl
import com.tracel.api.admin.Migration
import com.tracel.api.admin.PurgeReport
import com.tracel.api.admin.PurgeRequest
import com.tracel.api.admin.PurgeSummary
import com.tracel.api.admin.Snapshot
import com.tracel.api.admin.StoreStatus
import com.tracel.api.admin.TransferSummary
import com.tracel.api.result.Res
import com.tracel.engine.foreign.NoRoomForImport
import com.tracel.engine.store.PurgeFilter
import com.tracel.engine.store.PurgeSpec
import com.tracel.engine.store.StoppedByRequest
import com.tracel.model.world.WorldId
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.status.health.LAG_PROBE_MILLIS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Bukkit
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.ReadOnlyFileSystemException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Level
import kotlin.time.Duration.Companion.milliseconds
import com.tracel.api.admin.PurgeCategory as ApiCategory
import com.tracel.api.admin.PurgeTally as ApiTally
import com.tracel.engine.store.ExportSummary as StoredExport
import com.tracel.engine.store.PurgeCategory as StoredCategory
import com.tracel.engine.store.PurgeReport as StoredReport

private const val GATE_POLL_MILLIS = 500L
private const val SNAPSHOT_SUFFIX = ".tracel"

/**
 * [Admin] over the store, for one plugin or for the commands:
 * `/tracel data` and `/tracel status` run through this too.
 *
 * What it destroys is written to the server log with [signer]'s name.
 */
internal class TracelAdmin(
    private val services: TracelServices,
    private val signer: String,
    private val alive: () -> Boolean = { true },
) : Admin {
    override val exportDirectory: Path get() = services.exportDirectory

    override val storeBytes: Long get() = services.store.liveBytes

    override val migration: Migration by lazy { CoreProtectMigration(services, signer, alive, ::claim, ::release) }

    override suspend fun status(): Res<StoreStatus, AdminError> = guarded { Res.Ok(read()) }

    override suspend fun previewPurge(request: PurgeRequest): Res<PurgeReport, AdminError> = guarded {
        services.flushCapture()
        Res.Ok(shown(services.store.previewPurge(specOf(request))))
    }

    override suspend fun purge(
        request: PurgeRequest,
        onStart: () -> Unit,
        onWait: () -> Unit,
    ): Res<PurgeReport, AdminError> = exclusive {
        onStart()
        services.plugin.logger.info("$signer purges ${request.categories.joinToString { it.name.lowercase() }}.")
        services.purgeGate.awaitIdle(onWait)
        services.flushCapture()
        val report = services.store.purgeSome(specOf(request)) { slice -> services.purgeGate.slice(slice = slice) }
        Res.Ok(shown(report))
    }

    override suspend fun purgeEverything(onWait: () -> Unit): Res<PurgeSummary, AdminError> = exclusive {
        services.plugin.logger.info("$signer purges all history.")
        var told = false
        while (!services.composite.claimGate()) {
            if (!told) onWait()
            told = true
            delay(GATE_POLL_MILLIS.milliseconds)
        }
        try {
            val summary = services.store.purgeAll()
            services.differ.forgetAll()
            Res.Ok(PurgeSummary(summary.rows, summary.bytes, instant(summary.oldest), instant(summary.newest)))
        } finally {
            services.composite.releaseGate()
        }
    }

    override suspend fun export(stopped: () -> Boolean): Res<TransferSummary, AdminError> = guarded {
        val name = "database-%s.tracel".format(LocalDateTime.now().format(STAMP))
        transfer { services.store.exportTo(services.exportDirectory.resolve(name), stopped) }
    }

    override suspend fun snapshot(name: String): Res<Snapshot, AdminError> = guarded {
        val file = snapshotFile(name) ?: return@guarded Res.Err(AdminError.BadName(name))
        Res.Ok(Snapshot(name, file, runCatching { Files.size(file) }.getOrDefault(0L)))
    }

    override suspend fun importSnapshot(
        name: String,
        control: ImportControl,
        onStart: () -> Unit,
    ): Res<TransferSummary, AdminError> {
        if (!alive()) return Res.Err(AdminError.Revoked)
        val file = snapshotFile(name) ?: return Res.Err(AdminError.BadName(name))
        if (!claim()) return Res.Err(AdminError.Busy)
        if (!services.composite.claimGate()) {
            release()
            return Res.Err(AdminError.Busy)
        }
        try {
            onStart()
            services.plugin.logger.info("$signer replaces the history with $name.")
            val done = transfer { services.store.importFrom(file, control::stopRequested, control::commit) }
            if (done is Res.Ok) services.differ.forgetAll()
            return done
        } finally {
            services.composite.releaseGate()
            release()
        }
    }

    private fun claim(): Boolean = services.purging.compareAndSet(false, true)

    private fun release() = services.purging.set(false)

    private suspend fun <T> exclusive(work: suspend () -> Res<T, AdminError>): Res<T, AdminError> {
        if (!alive()) return Res.Err(AdminError.Revoked)
        if (!claim()) return Res.Err(AdminError.Busy)
        return try {
            fail(work)
        } finally {
            release()
        }
    }

    private suspend fun <T> guarded(work: suspend () -> Res<T, AdminError>): Res<T, AdminError> =
        if (alive()) fail(work) else Res.Err(AdminError.Revoked)

    private suspend fun <T> fail(work: suspend () -> Res<T, AdminError>): Res<T, AdminError> = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Res.Err(errorOf(failure))
    }

    private suspend fun transfer(work: suspend () -> StoredExport): Res<TransferSummary, AdminError> = try {
        val done = work()
        Res.Ok(TransferSummary(done.rows, done.bytes, done.file, instant(done.oldest), instant(done.newest)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Res.Err(errorOf(failure))
    }

    private fun errorOf(failure: Exception): AdminError = when {
        failure is StoppedByRequest -> AdminError.Stopped
        failure is AccessDeniedException || failure is ReadOnlyFileSystemException ->
            AdminError.Denied(services.exportDirectory)
        failure is NoSuchFileException -> AdminError.NoSuchFile(failure.file.substringAfterLast('/'))
        failure is FileSystemException && failure.reason?.contains("No space left") == true -> AdminError.NoSpace
        failure is NoRoomForImport -> AdminError.NoRoom
        else -> {
            Telemetry.error(failure)
            services.plugin.logger.log(Level.WARNING, "An admin call of $signer failed.", failure)
            AdminError.Failed(failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun snapshotFile(name: String): Path? {
        if (name.isBlank() || !name.endsWith(SNAPSHOT_SUFFIX)) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val candidate = services.exportDirectory.resolve(name).normalize()
        if (candidate.parent != services.exportDirectory.normalize()) return null
        return candidate.takeIf { Files.isRegularFile(it) }
    }

    private fun specOf(request: PurgeRequest): PurgeSpec = PurgeSpec(
        request.categories.mapTo(HashSet()) { StoredCategory.valueOf(it.name) },
        PurgeFilter(request.before?.toEpochMilli(), request.world?.let(::WorldId), request.player),
    )

    private fun shown(stored: StoredReport): PurgeReport = PurgeReport(
        stored.tallies.entries.associate { (category, tally) ->
            ApiCategory.valueOf(category.name) to ApiTally(tally.total, tally.matched, tally.rows, tally.bytes)
        },
        instant(stored.oldest),
        instant(stored.newest),
    )

    private suspend fun read(): StoreStatus {
        val stored = withContext(Dispatchers.IO) { services.store.report() }
        val database = services.plugin.dataFolder.resolve("database")

        // What waits right now, before the wait below changes it
        val queued =
            services.pendingCaptures.owedNow() + services.store.capture.backlog.coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        // How late the queue is is how long it takes to empty
        val started = System.nanoTime()
        val applied =
            withTimeoutOrNull(LAG_PROBE_MILLIS) { services.store.capture.awaitApplied(LAG_PROBE_MILLIS) } == true
        val left = (LAG_PROBE_MILLIS - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
        val settled = withTimeoutOrNull(left) { services.pendingCaptures.await(left) } == true
        val took = (System.nanoTime() - started) / 1_000_000
        val beyond = !applied || !settled
        val lag = if (beyond) LAG_PROBE_MILLIS else took

        val purge = services.purgeSettings
        return StoreStatus(
            at = Instant.now(),
            blockRows = stored.blockRows,
            itemRows = stored.itemRows,
            eventRows = stored.eventRows,
            oldest = instant(stored.oldestMillis),
            databaseBytes = stored.liveBytes,
            diskFree = database.usableSpace,
            diskTotal = database.totalSpace,
            queued = queued,
            writesPerSecond = services.writeRate.perSecond(),
            rollbacks = services.composite.activeRollbacks,
            autoPurge = AutoPurgeStatus(
                purge.enabled,
                Duration.ofMillis(purge.intervalMillis),
                purge.keep.entries.associate { (category, keep) ->
                    ApiCategory.valueOf(category.name) to keep?.let(Duration::ofMillis)
                },
                instant(services.lastPurgeMillis),
            ),
            lagMillis = lag,
            lagBeyondProbe = beyond,
            mspt = runCatching { Bukkit.getAverageTickTime() }.getOrNull(),
            forwardCompatible = services.forwardCompatible,
            format = services.store.formatVersion,
        )
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    }
}

internal fun instant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)
