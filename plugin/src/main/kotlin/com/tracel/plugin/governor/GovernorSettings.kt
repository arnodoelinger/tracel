package com.tracel.plugin.governor

/**
 * How long a rollback may hold a region in one tick: never less than [minNanos], so it always makes progress, and
 * never more than [maxNanos]. A tick that stays under 50 ms costs no TPS, so these are limits on how long a tick may
 * get, not a share of the server.
 */
data class GovernorSettings(
    val minNanos: Long = 5_000_000L,
    val maxNanos: Long = 35_000_000L,
)
