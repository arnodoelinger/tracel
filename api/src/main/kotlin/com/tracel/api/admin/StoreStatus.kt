package com.tracel.api.admin

import com.tracel.api.Unstable
import java.time.Duration
import java.time.Instant

/**
 * The automatic purge.
 *
 * Off unless asked, and how long each category is kept; `null` is for ever.
 */
@Unstable
public data class AutoPurgeStatus(
    public val enabled: Boolean,
    public val interval: Duration,
    public val keep: Map<PurgeCategory, Duration?>,
    public val last: Instant?,
)

/** How the store is doing right now. */
@Unstable
public data class StoreStatus(
    public val at: Instant,
    public val blockRows: Long,
    public val itemRows: Long,
    public val eventRows: Long,
    public val oldest: Instant?,
    public val databaseBytes: Long,
    public val diskFree: Long,
    public val diskTotal: Long,
    public val queued: Int,
    public val writesPerSecond: Double?,
    public val rollbacks: List<Long>,
    public val autoPurge: AutoPurgeStatus,
    public val lagMillis: Long?,
    public val lagBeyondProbe: Boolean,
    public val mspt: Double?,
    public val forwardCompatible: Boolean,
    public val format: String,
)
