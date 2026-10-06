package com.tracel.plugin.util.log

import com.tracel.plugin.metrics.Telemetry
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Logs a warning only once for each key. */
internal object Warnings {
    private val said = ConcurrentHashMap.newKeySet<String>()

    /** Logs a warning. */
    fun once(logger: Logger, key: String, message: () -> String) {
        if (!said.add(key)) return
        Telemetry.warning(key)
        logger.warning(message())
    }
}
