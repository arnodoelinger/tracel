package com.tracel.plugin.command.presenter

import com.tracel.plugin.config.AutoPurgeSettings
import com.tracel.plugin.status.disk.DiskLevel
import com.tracel.plugin.status.health.Health

/** What `/tracel status` reads off the plugin, before it is turned into lines. */
internal class StatusSnapshot(
    val nowMillis: Long,
    val blockRows: Long,
    val itemRows: Long,
    val eventRows: Long,
    val oldestMillis: Long?,
    val databaseBytes: Long,
    val diskFree: Long,
    val diskTotal: Long,
    val queued: Int,
    val writesPerSecond: Double?,
    val rollbacks: List<Long>,
    val purge: AutoPurgeSettings,
    val lastPurgeMillis: Long?,
    val lagMillis: Long?,
    val lagBeyondProbe: Boolean,
    val mspt: Double?,
    val forwardCompatible: Boolean,
    val format: String,
) {
    val disk: DiskLevel get() = DiskLevel.of(diskFree, diskTotal)
    val health: Health get() = Health.of(forwardCompatible, mspt, lagMillis, disk)
}
