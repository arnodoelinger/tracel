package com.tracel.plugin

import com.tracel.engine.ledger.LotLedger
import com.tracel.plugin.command.RollbackPreviewCommand
import com.tracel.plugin.listener.HopperTransferListener
import com.tracel.plugin.listener.InventoryClickCaptureListener
import com.tracel.plugin.scheduler.PaperTracelSchedulers
import com.tracel.storage.TracelDatabase
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.log.SqliteTransactionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    private lateinit var database: TracelDatabase
    private lateinit var services: TracelServices

    override fun onEnable() {
        val schedulers = PaperTracelSchedulers(this)

        dataFolder.mkdirs()
        database = TracelDatabase.open(dataFolder.resolve("tracel.db").toPath())

        val repo = SqliteLotRepository(database.exposed)
        services = TracelServices(
            repo = repo,
            ledger = LotLedger(repo),
            log = SqliteTransactionLog(database.exposed),
            counters = SqliteCounters(database.exposed),
            schedulers = schedulers,
            scope = CoroutineScope(SupervisorJob() + schedulers.async),
        )

        server.pluginManager.registerEvents(HopperTransferListener(services), this)
        server.pluginManager.registerEvents(InventoryClickCaptureListener(services, this), this)
        registerCommand("tracel", "Tracel's forensics and rollback commands.", RollbackPreviewCommand(services))

        logger.info("Tracel ${pluginMeta.version} enabled.")
    }

    override fun onDisable() {
        if (::services.isInitialized) services.scope.cancel()
        if (::database.isInitialized) database.close()
        logger.info("Tracel disabled.")
    }
}
