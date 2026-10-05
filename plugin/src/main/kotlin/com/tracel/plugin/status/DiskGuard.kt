package com.tracel.plugin.status

import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.TracelServices
import com.tracel.plugin.util.killServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** How often the disk is looked at while the server runs. */
private const val CHECK_MILLIS = 10_000L

/** How many bytes in a gibibyte. */
private const val GIB = 1024.0 * 1024.0 * 1024.0

/** There is no room left to write history, so the server may neither start nor go on. */
class CriticalDiskSpace(message: String) : IllegalStateException(message)

/**
 * Keeps the server off a disk with nowhere to write: below [FATAL_DISK_PERCENT] free it will not start, and a server
 * that is running when it gets there is stopped.
 */
internal object DiskGuard {
    /** Refuses to go on if the disk [database] lives on is below the line. Run before anything is opened. */
    fun requireRoom(database: File) {
        val (free, total) = spaceOf(database)
        if (DiskLevel.fatal(free, total)) throw CriticalDiskSpace(message(free, total, starting = true))
    }

    /** Looks at the disk every few seconds, and stops the server when it falls below the line. */
    fun start(plugin: TracelPlugin, services: TracelServices, database: File): Job = services.scope.launch {
        while (isActive) {
            delay(CHECK_MILLIS.milliseconds)
            val (free, total) = spaceOf(database)
            if (!DiskLevel.fatal(free, total)) continue
            plugin.logger.severe(message(free, total, starting = false))
            killServer(plugin)
            return@launch
        }
    }

    private fun spaceOf(database: File): Pair<Long, Long> {
        val place = generateSequence(database.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: database
        return place.usableSpace to place.totalSpace
    }

    private fun message(free: Long, total: Long, starting: Boolean): String {
        val left = "%.2f%% (%.1f of %.1f GB)".format(Locale.ENGLISH, free * 100.0 / total, free / GIB, total / GIB)
        val what = if (starting) "The server will not start" else "Stopping the server"
        return "Disk almost full: only $left free where Tracel keeps its database. $what, because there is " +
                "nowhere to write history. Delete files you don't need, then start the server again or run \"/tracel data purge\"."
    }
}
