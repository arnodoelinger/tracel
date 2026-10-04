package com.tracel.plugin.status

/** How late the capture queue has to be before `/tracel status` calls the plugin degraded. */
const val LAG_MILLIS = 5_000L

/** How long `/tracel status` waits for the queue to settle before it says only that it is later than that. */
const val LAG_PROBE_MILLIS = 15_000L

/** Average tick time, in milliseconds, from which the server counts as heavily loaded. */
const val HIGH_MSPT = 45.0

/** How big a rollback is, by how many records it takes back. */
enum class RollbackSize(val key: String, private val below: Long) {
    TINY("tiny", 1_024),
    SMALL("small", 10_240),
    MEDIUM("medium", 102_400),
    LARGE("large", 1_024_000),
    HUGE("huge", 8_192_000);

    companion object {
        /** The size of a rollback that takes back [records] records. */
        fun of(records: Long): RollbackSize = entries.first { records < it.below }
    }
}

/** How much room is left where the database lives. */
enum class DiskLevel {
    OK,
    LOW,
    CRITICAL;

    companion object {
        /** Critical below 2% free, low below 5%. */
        fun of(free: Long, total: Long): DiskLevel = when {
            total <= 0L -> OK
            free * 100 < total * 2 -> CRITICAL
            free * 100 < total * 5 -> LOW
            else -> OK
        }
    }
}

/** The one word `/tracel status` has for how the plugin is doing. Several may be true; the worst is shown. */
enum class Health(val key: String) {
    OK("ok"),
    FORWARD_COMPATIBLE("forward"),
    HIGH_LOAD("load"),
    DEGRADED("degraded"),
    LOW_DISK("low_disk"),
    CRITICAL_DISK("critical_disk");

    companion object {
        /**
         * The worst of what is `true`: a disk about to fill, then a capture queue that is late, then the low disk, the
         * busy server, and last that the server is newer than anything `Tracel` was tested on.
         *
         * @param lagMillis how long the capture queue took to settle, or `null` if it was already empty
         */
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
