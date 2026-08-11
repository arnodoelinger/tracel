package com.tracel.plugin

import com.tracel.plugin.scheduler.PaperTracelSchedulers
import com.tracel.storage.TracelDatabase
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    private lateinit var schedulers: PaperTracelSchedulers
    private lateinit var database: TracelDatabase

    override fun onEnable() {
        schedulers = PaperTracelSchedulers(this)

        dataFolder.mkdirs()
        database = TracelDatabase.open(dataFolder.resolve("tracel.db").toPath())

        logger.info("Tracel ${pluginMeta.version} enabled.")
    }

    override fun onDisable() {
        if (::database.isInitialized) database.close()
        logger.info("Tracel disabled.")
    }
}
