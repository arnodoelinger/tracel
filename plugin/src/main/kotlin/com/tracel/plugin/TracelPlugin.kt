package com.tracel.plugin

import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackJobCoordinator
import com.tracel.engine.rollback.WorldQuery
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.plugin.command.TracelCommand
import com.tracel.plugin.listener.capture.BlockPlacementCaptureListener
import com.tracel.plugin.listener.capture.ContainerBreakListener
import com.tracel.plugin.listener.capture.CraftCaptureListener
import com.tracel.plugin.listener.capture.HopperTransferListener
import com.tracel.plugin.listener.capture.InventoryClickCaptureListener
import com.tracel.plugin.listener.capture.ItemEntityCaptureListener
import com.tracel.plugin.listener.delivery.PendingDeliveryListener
import com.tracel.plugin.listener.explosion.ExplosionCaptureListener
import com.tracel.plugin.listener.inspect.InspectListener
import com.tracel.plugin.listener.redstone.RedstoneTriggerListener
import com.tracel.plugin.scheduler.PaperTracelSchedulers
import com.tracel.plugin.startup.ItemKeyStabilityCanary
import com.tracel.storage.TracelDatabase
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.journal.SqliteInvolutionJournal
import com.tracel.storage.journal.SqliteJournal
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.log.SqliteTransactionLog
import com.tracel.storage.ownership.SqliteLotLeaseRegistry
import com.tracel.storage.pending.SqlitePendingDeliveryRepository
import com.tracel.storage.rollback.SqliteRollbackJobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    private lateinit var database: TracelDatabase
    private lateinit var services: TracelServices

    override fun onEnable() {
        ItemKeyStabilityCanary.check(this, logger)

        val schedulers = PaperTracelSchedulers(this)

        dataFolder.mkdirs()
        database = TracelDatabase.open(dataFolder.resolve("tracel.db").toPath())

        val repo = SqliteLotRepository(database.exposed)
        val ledger = LotLedger(repo)
        val log = SqliteTransactionLog(database.exposed)
        val counters = SqliteCounters(database.exposed)
        val leases = SqliteLotLeaseRegistry(database.exposed)
        val jobs = SqliteRollbackJobRepository(database.exposed)
        val pendingDeliveries = SqlitePendingDeliveryRepository(database.exposed)
        val journalExecutor = JournalExecutor(
            RollbackExecutor(ledger, log, counters::nextSeq),
            SqliteJournal(database.exposed),
            leases,
            counters::nextTxnId,
        )
        val involutionCoordinator = InvolutionJobCoordinator(
            jobs,
            repo,
            leases,
            InvolutionExecutor(ledger, log, counters::nextSeq),
            SqliteInvolutionJournal(database.exposed),
            counters::nextTxnId,
        )

        services = TracelServices(
            repo = repo,
            ledger = ledger,
            log = log,
            counters = counters,
            schedulers = schedulers,
            scope = CoroutineScope(SupervisorJob() + schedulers.async),
            rollback = RollbackJobCoordinator(repo, WorldQuery { Bukkit.getPlayer(it) != null }, leases, journalExecutor, jobs),
            jobs = jobs,
            undo = involutionCoordinator,
            pendingDeliveries = pendingDeliveries,
            db = database.exposed,
        )

        server.pluginManager.registerEvents(HopperTransferListener(services), this)
        server.pluginManager.registerEvents(PendingDeliveryListener(services), this)
        server.pluginManager.registerEvents(InventoryClickCaptureListener(services, this), this)
        server.pluginManager.registerEvents(CraftCaptureListener(services, this), this)
        server.pluginManager.registerEvents(ContainerBreakListener(services), this)
        server.pluginManager.registerEvents(ItemEntityCaptureListener(services, this), this)
        server.pluginManager.registerEvents(BlockPlacementCaptureListener(services), this)
        server.pluginManager.registerEvents(ExplosionCaptureListener(services), this)
        server.pluginManager.registerEvents(RedstoneTriggerListener(services), this)
        server.pluginManager.registerEvents(InspectListener(services), this)
        registerCommand("tracel", "Tracel's forensics and rollback commands.", TracelCommand(services))

        logger.info("Tracel ${pluginMeta.version} enabled.")
    }

    override fun onDisable() {
        if (::services.isInitialized) services.scope.cancel()
        if (::database.isInitialized) database.close()
        logger.info("Tracel disabled.")
    }
}
