package com.tracel.plugin.util.runtime

import java.util.logging.Level
import java.util.logging.Logger

/** Runs [step] without preventing later shutdown steps from running. */
internal inline fun stopping(logger: Logger, what: String, step: () -> Unit) {
    try {
        step()
    } catch (failure: Throwable) {
        logger.log(Level.WARNING, "Could not shut down $what cleanly (${failure.messageOrName()}). Shutdown goes on.", failure)
    }
}
