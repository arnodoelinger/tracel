package com.tracel.plugin.api.admin

import com.tracel.api.admin.AdminError
import com.tracel.api.admin.Migration
import com.tracel.api.admin.MigrationOutcome
import com.tracel.api.admin.MigrationOutlook
import com.tracel.api.admin.MigrationSource
import com.tracel.api.result.Res
import com.tracel.engine.foreign.NoRoomForImport
import com.tracel.plugin.integration.coreprotect.CoreProtectImport
import com.tracel.plugin.integration.coreprotect.source.CoreProtectDatabase
import com.tracel.plugin.integration.coreprotect.source.CoreProtectLocation
import com.tracel.plugin.integration.coreprotect.source.CoreProtectLocator
import com.tracel.plugin.integration.coreprotect.translate.ServerImportPlatform
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.services.monitoring.Telemetry
import com.tracel.plugin.util.runtime.messageOrName
import com.tracel.plugin.util.runtime.rescue
import com.tracel.plugin.util.units.MILLIS_PER_SECOND
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.logging.Level

/** [Migration] over what `CoreProtect` left in its folder. */
internal class CoreProtectMigration(
    private val services: TracelServices,
    private val signer: String,
    private val alive: () -> Boolean,
    private val claim: () -> Boolean,
    private val release: () -> Unit,
) : Migration {
    private val importer =
        CoreProtectImport(services.storage.foreign, ServerImportPlatform) { work -> services.purgeGate.slice(slice = work) }

    private class Source(val location: CoreProtectLocation) : MigrationSource {
        override fun toString(): String = location.toString()
    }

    override fun locate(): Res<MigrationSource, AdminError> {
        if (!alive()) return Res.Err(AdminError.Revoked)
        val plugins = services.plugin.dataFolder.toPath().toAbsolutePath().parent
        return CoreProtectLocator.find(plugins)?.let { Res.Ok(Source(it)) } ?: Res.Err(AdminError.NoSource)
    }

    override suspend fun outlook(source: MigrationSource): Res<MigrationOutlook, AdminError> {
        if (!alive()) return Res.Err(AdminError.Revoked)
        val location = (source as Source).location
        return rescue({ failure -> Res.Err(errorOf(failure)) }) {
            val outlook = withContext(Dispatchers.IO) { CoreProtectDatabase.open(location).use { importer.outlook(it) } }
            Res.Ok(
                MigrationOutlook(
                    outlook.left.sum(),
                    outlook.span?.let { (oldest, newest) ->
                        Instant.ofEpochMilli(oldest * MILLIS_PER_SECOND) to Instant.ofEpochMilli(newest * MILLIS_PER_SECOND)
                    },
                ),
            )
        }
    }

    override suspend fun run(
        source: MigrationSource,
        stop: () -> Boolean,
        onStart: () -> Unit,
        onWait: () -> Unit,
        progress: (done: Long, of: Long) -> Unit,
    ): Res<MigrationOutcome, AdminError> {
        if (!alive()) return Res.Err(AdminError.Revoked)
        val location = (source as Source).location
        if (!claim()) return Res.Err(AdminError.Busy)
        try {
            onStart()
            services.plugin.logger.info("CoreProtect import from $location started by $signer.")
            services.purgeGate.awaitIdle(onWait)
            val outcome = withContext(Dispatchers.IO) {
                CoreProtectDatabase.open(location).use { database -> importer.run(database, stop, progress) }
            }
            val tally = outcome.tally
            val taken = tally.taken.values.sum()
            services.plugin.logger.info(
                "CoreProtect import ${if (outcome.stopped) "paused" else "finished"}: " +
                        "$taken records from ${tally.rows} rows in ${outcome.tookMillis / MILLIS_PER_SECOND} s.",
            )
            return Res.Ok(
                MigrationOutcome(
                    stopped = outcome.stopped,
                    tookMillis = outcome.tookMillis,
                    rows = tally.rows,
                    taken = taken,
                    skipped = tally.skipped.values.sum(),
                    oldest = tally.oldest.takeIf { it <= tally.newest }?.let(Instant::ofEpochMilli),
                    newest = tally.newest.takeIf { tally.oldest <= it }?.let(Instant::ofEpochMilli),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Res.Err(errorOf(failure))
        } finally {
            release()
        }
    }

    private fun errorOf(failure: Exception): AdminError {
        if (failure is NoRoomForImport) {
            services.plugin.logger.warning(
                "CoreProtect import refused. This database numbers its own history from the start.",
            )
            return AdminError.NoRoom
        }
        Telemetry.error(failure)
        services.plugin.logger.log(Level.WARNING, "CoreProtect import failed (${failure.messageOrName()}).", failure)
        return AdminError.Failed(failure.messageOrName())
    }
}
