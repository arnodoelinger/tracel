package com.tracel.plugin.services.monitoring

import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.bukkit.server.killServer
import com.tracel.plugin.services.TracelServices
import java.io.File
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CHECK_MILLIS = 10_000L
private const val GIB = 1024.0 * 1024.0 * 1024.0

const val FATAL_DISK_PERCENT = 1
const val LAG_MILLIS = 5_000L
const val LAG_PROBE_MILLIS = 15_000L
const val HIGH_MSPT = 45.0

/** A disk that is so full the `Tracel` cannot write to it anymore. */
class CriticalDiskSpace(message: String) : IllegalStateException(message)

/** How much room is left where the database lives. */
enum class DiskLevel {
    OK,
    LOW,
    CRITICAL;

    companion object {
        fun fatal(free: Long, total: Long): Boolean = total > 0L && free * 100 < total * FATAL_DISK_PERCENT

        fun of(free: Long, total: Long): DiskLevel = when {
            total <= 0L -> OK
            free * 100 < total * 2 -> CRITICAL
            free * 100 < total * 5 -> LOW
            else -> OK
        }
    }
}

/**
 * The one word `/tracel status` has for how the plugin is doing.
 *
 * Several may be true; the worst is shown.
 */
enum class Health(val key: String) {
    OK("ok"),
    FORWARD_COMPATIBLE("forward"),
    HIGH_LOAD("load"),
    DEGRADED("degraded"),
    LOW_DISK("low_disk"),
    CRITICAL_DISK("critical_disk");

    companion object {
        fun of(forwardCompatible: Boolean, mspt: Double?, lagMillis: Long?, disk: DiskLevel): Health = when {
            disk == DiskLevel.CRITICAL -> CRITICAL_DISK
            lagMillis != null && lagMillis >= LAG_MILLIS -> DEGRADED
            disk == DiskLevel.LOW -> LOW_DISK
            mspt != null && mspt >= HIGH_MSPT -> HIGH_LOAD
            forwardCompatible -> FORWARD_COMPATIBLE
            else -> OK
        }
    }
}

/** How big a rollback is, by how many records it takes back. */
enum class RollbackSize(val key: String, private val below: Long) {
    TINY("tiny", 1_024),
    SMALL("small", 10_240),
    MEDIUM("medium", 102_400),
    LARGE("large", 1_024_000),
    HUGE("huge", 8_192_000);

    companion object {
        fun of(records: Long): RollbackSize = entries.firstOrNull { records < it.below } ?: HUGE
    }
}

/**
 * Keeps the server off a disk with nowhere to write: below [FATAL_DISK_PERCENT] free it will not start, and a server
 * running when it gets there is stopped.
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

/** Counts how fast the store takes writes: samples of a running total, read as a rate over the last minute. */
class WriteRate(
    private val total: () -> Long,
    private val keep: Int = 13,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Notes the running total now. */
    @Synchronized
    fun sample() {
        val now = clock()
        val count = runCatching(total).getOrNull() ?: return
        samples.addLast(now to count)
        while (samples.size > keep) samples.removeFirst()
    }

    /** Writes per second between the oldest and newest sample, or `null` before there are two. */
    @Synchronized
    fun perSecond(): Double? {
        if (samples.size < 2) return null
        val (from, first) = samples.first()
        val (to, last) = samples.last()
        if (to <= from) return null
        return (last - first) * 1000.0 / (to - from)
    }
}
