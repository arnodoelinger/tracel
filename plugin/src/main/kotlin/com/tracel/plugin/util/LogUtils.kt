package com.tracel.plugin.util

import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Logs a warning only once for each key. */
internal object Warnings {
    private val said = ConcurrentHashMap.newKeySet<String>()

    /** Logs a warning. */
    fun once(logger: Logger, key: String, message: () -> String) {
        if (!said.add(key)) return
        logger.warning(message())
    }
}
