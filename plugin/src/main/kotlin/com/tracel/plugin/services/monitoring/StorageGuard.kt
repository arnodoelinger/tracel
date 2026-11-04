package com.tracel.plugin.services.monitoring

import com.tracel.engine.store.WritesStop
import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.bukkit.server.killServer
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val GUARD_CHECK_MILLIS = 1_000L

const val WRITES_STOPPED_GRACE_MILLIS = 30_000L

/** Stops the server when something went wrong and `Tracel` cannot write history anymore. */
internal object StorageGuard {
    fun start(plugin: TracelPlugin, services: TracelServices): Job = services.scope.launch {
        val store = services.storage.store
        val entities = services.capturing.entityCapture
        fun lost() = store.capture.dropped + entities.overflowed
        val lostBefore = lost()
        while (isActive) {
            delay(GUARD_CHECK_MILLIS.milliseconds)
            val why = verdict(store.writesStopped, lost() - lostBefore, System.currentTimeMillis()) ?: continue
            plugin.logger.severe("$why The server has been stopped to prevent further damage.")
            killServer(plugin)
            return@launch
        }
    }

    /** Why the server has to go down now, or `null` if it does not. */
    fun verdict(stop: WritesStop?, lost: Long, now: Long): String? = when {
        stop != null && stop.fatal ->
            "Fatal error: the disk failed (${stop.reason}). History can no longer be written."

        stop != null && now - stop.since >= WRITES_STOPPED_GRACE_MILLIS ->
            "Fatal error: the disk has refused writes for ${(now - stop.since) / 1000} s (${stop.reason}). History can no longer be written."

        lost > 0 ->
            "Fatal error: $lost captured events were lost. History has a hole in it."

        else -> null
    }
}
