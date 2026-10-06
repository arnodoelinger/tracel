package com.tracel.plugin.status.health

/** How late the capture queue has to be before `/tracel status` calls the plugin degraded. */
const val LAG_MILLIS = 5_000L

/** How long `/tracel status` waits for the queue to settle before it says only that it is later than that. */
const val LAG_PROBE_MILLIS = 15_000L

/** Average tick time, in milliseconds, from which the server counts as heavily loaded. */
const val HIGH_MSPT = 45.0
