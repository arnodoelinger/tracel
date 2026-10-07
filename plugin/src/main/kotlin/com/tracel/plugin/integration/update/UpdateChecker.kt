package com.tracel.plugin.integration.update

import com.tracel.plugin.services.TracelServices
import io.github.z4kn4fein.semver.toVersionOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Level
import kotlin.time.Duration.Companion.hours

/** How long between two looks at `GitHub`. */
private val EVERY = 12.hours

/** Looks for a newer `Tracel` now and then, and remembers the newest it found. */
internal class UpdateChecker(private val services: TracelServices) {
    /** The release to tell operators about, or `null` if this one is the newest known. */
    @Volatile
    var available: Release? = null; private set

    /** The version this server runs. */
    val running: String = services.plugin.pluginMeta.version

    /** Starts the periodic check. A server whose version cannot be read has nothing to compare, so it does not start. */
    fun start() {
        val current = running.toVersionOrNull(strict = false) ?: return
        services.scope.launch {
            while (isActive) {
                try {
                    val found = ReleaseFeed.newerThan(current)
                    if (found != null && found.version != available?.version) {
                        services.plugin.logger.info(
                            "Update available: $running → ${found.version} — ${found.page}"
                        )
                    }
                    available = found
                } catch (failure: Exception) {
                    services.plugin.logger.log(Level.FINE, "Could not look for a Tracel update: $failure")
                }
                delay(EVERY)
            }
        }
    }
}
