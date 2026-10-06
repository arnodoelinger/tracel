package com.tracel.plugin.status.disk

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
