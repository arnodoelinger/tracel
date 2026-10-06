package com.tracel.plugin.config

/** What gets written to the history. Every kind is on unless it is turned off. */
data class LoggingSettings(
    val blocks: Boolean = true,
    val items: Boolean = true,
    val entities: Boolean = true,
    val events: Boolean = true,
    val worldEdit: Boolean = true,
)
