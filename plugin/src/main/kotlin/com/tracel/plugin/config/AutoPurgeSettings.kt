package com.tracel.plugin.config

import com.tracel.engine.store.PurgeCategory

/** The automatic purge: off unless asked, and how long each category of history is [keep]t. */
data class AutoPurgeSettings(
    val enabled: Boolean = false,
    val intervalMillis: Long = DEFAULT_PURGE_INTERVAL_MILLIS,
    val keep: Map<PurgeCategory, Long?> = PurgeCategory.entries.associateWith { DEFAULT_PURGE_KEEP_MILLIS },
)
