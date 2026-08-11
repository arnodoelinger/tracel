package com.tracel.plugin

import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    override fun onEnable() {
        logger.info("Tracel ${pluginMeta.version} enabled.")
    }

    override fun onDisable() {
        logger.info("Tracel disabled.")
    }
}
