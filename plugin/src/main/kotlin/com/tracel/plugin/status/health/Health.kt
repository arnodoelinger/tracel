package com.tracel.plugin.status.health

import com.tracel.plugin.status.disk.DiskLevel

/** The one word `/tracel status` has for how the plugin is doing. Several may be true; the worst is shown. */
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
