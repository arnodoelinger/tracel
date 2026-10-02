package com.tracel.plugin.startup

import com.tracel.plugin.AutoPurgeSettings
import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.TracelServices
import com.tracel.storage.ports.ops.PurgeCategory
import com.tracel.storage.ports.ops.PurgeFilter
import com.tracel.storage.ports.ops.PurgeSpec
import com.tracel.storage.ports.ops.purgeSome
import com.tracel.storage.ports.ops.reclaimSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Level
import kotlin.time.Duration.Companion.milliseconds

private const val FIRST_RUN_DELAY_MILLIS = 5L * 60_000

/** Every `interval`, deletes what is older than `keep-*` for each category, for as long as the plugin lives. */
internal fun startAutoPurge(plugin: TracelPlugin, services: TracelServices, settings: AutoPurgeSettings): Job? {
    if (!settings.enabled) return null
    val keep = settings.keep.mapNotNull { (category, millis) -> millis?.let { category to it } }
    if (keep.isEmpty()) {
        plugin.logger.warning("Automatic purge is on, but every keep-* is \"forever\"; nothing to do.")
        return null
    }
    plugin.logger.info("Automatic purge: on, every ${settings.intervalMillis / 60_000} min.")
    return services.scope.launch {
        delay(FIRST_RUN_DELAY_MILLIS.milliseconds)
        while (isActive) {
            sweep(plugin, services, keep)
            delay(settings.intervalMillis.milliseconds)
        }
    }
}

private suspend fun sweep(plugin: TracelPlugin, services: TracelServices, keep: List<Pair<PurgeCategory, Long>>) {
    if (services.composite.isRunning || !services.purging.compareAndSet(false, true)) {
        plugin.logger.info("Automatic purge: skipped, a rollback or another purge is running.")
        return
    }
    try {
        services.flushCapture()
        val now = System.currentTimeMillis()
        var records = 0L
        var rows = 0L
        for ((category, millis) in keep) {
            val report = purgeSome(services.storage, PurgeSpec(setOf(category), PurgeFilter(now - millis)), compact = false)
            records += report.matched
            rows += report.rows
        }
        if (rows > 0) reclaimSpace(services.storage)
        if (records > 0) plugin.logger.info("Automatic purge: took $records record(s), $rows row(s).")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        plugin.logger.log(Level.WARNING, "Automatic purge failed: ${failure.message}.", failure)
    } finally {
        services.purging.set(false)
    }
}
