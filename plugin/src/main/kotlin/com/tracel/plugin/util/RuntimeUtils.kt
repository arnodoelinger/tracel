package com.tracel.plugin.util

import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/** Runs [step] without preventing later shutdown steps from running. */
internal inline fun stopping(logger: Logger, what: String, step: () -> Unit) {
    try {
        step()
    } catch (failure: Throwable) {
        logger.log(Level.WARNING, "Tracel could not shut down $what cleanly; the rest still goes", failure)
    }
}

/** `true` once the server itself has started shutting down. */
internal fun serverIsStopping(): Boolean =
    runCatching { Bukkit.isStopping() }.getOrElse {
        runCatching { Bukkit.getServer().isStopping }.getOrDefault(false)
    }

/** Server shutdown. */
internal fun killServer(plugin: Plugin) {
    plugin.logger.handlers.forEach { it.flush() }
    runCatching { Bukkit.shutdown() }
    runCatching {
        Bukkit.getGlobalRegionScheduler().execute(plugin) { Bukkit.shutdown() }
    }
}

/** Logs [message] as severe, shuts the server down, then throws to unwind the caller. */
internal fun fail(plugin: Plugin, message: String): Nothing {
    plugin.logger.severe(message)
    killServer(plugin)
    throw IllegalStateException(message)
}
