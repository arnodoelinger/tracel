package com.tracel.plugin.importer.coreprotect.source

import com.tracel.annotations.Unstable
import java.nio.file.Files
import java.nio.file.Path
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.*
import org.bukkit.inventory.meta.*

/** Finds the database `CoreProtect` itself would open, by reading its folder the way it does. */
@Unstable
object CoreProtectLocator {
    private const val FOLDER = "CoreProtect"
    private const val DATABASE = "database.db"
    private const val DEFAULT_PORT = 3306

    fun find(plugins: Path): CoreProtectLocation? {
        val folder = plugins.resolve(FOLDER)
        val configFile = folder.resolve("config.yml")
        val config =
            if (Files.isRegularFile(configFile)) YamlConfiguration.loadConfiguration(configFile.toFile()) else null
        val prefix = config?.getString("table-prefix")?.takeIf { it.isNotBlank() } ?: CoreProtectLocation.DEFAULT_PREFIX
        if (config?.getBoolean("use-mysql") == true) {
            return CoreProtectLocation.Server(
                host = config.getString("mysql-host") ?: "127.0.0.1",
                port = config.getInt("mysql-port", DEFAULT_PORT),
                database = config.getString("mysql-database") ?: "database",
                user = config.getString("mysql-username") ?: "root",
                password = config.getString("mysql-password").orEmpty(),
                prefix = prefix,
            )
        }
        val file = folder.resolve(DATABASE)
        return if (Files.isRegularFile(file)) CoreProtectLocation.File(file, prefix) else null
    }
}
