package com.tracel.plugin.startup

import com.tracel.plugin.AutoPurgeSettings
import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.shortSpan
import com.tracel.engine.store.PurgeCategory
import com.tracel.engine.store.PurgeFilter
import com.tracel.engine.store.PurgeSpec
import kotlinx.coroutines.*
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
    plugin.logger.info("Automatic purge: on, every ${shortSpan(settings.intervalMillis)}.")
    return services.scope.launch {
        delay(FIRST_RUN_DELAY_MILLIS.milliseconds)
        while (isActive) {
            sweep(plugin, services, keep)
            delay(settings.intervalMillis.milliseconds)
        }
    }
}

private suspend fun sweep(plugin: TracelPlugin, services: TracelServices, keep: List<Pair<PurgeCategory, Long>>) {
    if (!services.purging.compareAndSet(false, true)) {
        plugin.logger.info("Automatic purge: skipped, another purge or an import is running.")
        return
    }
    try {
        services.purgeGate.awaitIdle { plugin.logger.info("Automatic purge: waiting for a rollback to finish.") }
        services.flushCapture()
        val now = System.currentTimeMillis()
        var records = 0L
        var bytes = 0L
        for ((category, millis) in keep) {
            val spec = PurgeSpec(setOf(category), PurgeFilter(now - millis), wholeWindowsOnly = true)
            val report = services.store.purgeSome(spec) { slice -> services.purgeGate.slice(slice = slice) }
            records += report.matched
            bytes += report.bytes
        }
        services.lastPurgeMillis = System.currentTimeMillis()
        if (records > 0) plugin.logger.info("Automatic purge: took $records record(s), %.1f MiB.".format(bytes / (1024.0 * 1024.0)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        plugin.logger.log(Level.WARNING, "Automatic purge failed: ${failure.message}", failure)
    } finally {
        services.purging.set(false)
    }
}
