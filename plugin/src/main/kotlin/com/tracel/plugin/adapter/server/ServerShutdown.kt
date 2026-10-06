package com.tracel.plugin.adapter.server

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

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
