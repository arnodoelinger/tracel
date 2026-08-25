package com.tracel.plugin

import com.tracel.engine.capture.releaseFlows
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
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.capture.Drainer
import com.tracel.storage.ports.Counters
import com.tracel.storage.ports.Journal
import com.tracel.storage.ports.LotLeaseRegistry
import com.tracel.storage.ports.LotRepository
import com.tracel.storage.ports.RollbackJobRepository
import com.tracel.storage.ports.TransactionLog
import com.tracel.storage.ports.PendingDeliveryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    private lateinit var storage: TracelStorage
    private lateinit var services: TracelServices
    private lateinit var drain: Job

    override fun onEnable() {
        ItemKeyStabilityCanary.check(this, logger)

        dataFolder.mkdirs()
        storage = TracelStorage.open(dataFolder.resolve("ledger").toPath())

        // The store's own writer thread is the storage thread
        val schedulers = PaperTracelSchedulers(this, storage.dispatcher)

        val counters = Counters(storage)
        val repo = LotRepository(storage, counters)
        val ledger = LotLedger(repo)
        val log = TransactionLog(storage)
        val leases = LotLeaseRegistry(storage)
        val jobs = RollbackJobRepository(storage)
        val pendingDeliveries = PendingDeliveryRepository(storage, counters)
        val journalExecutor = JournalExecutor(
            RollbackExecutor(ledger, log, counters::nextSeq),
            Journal.forRollback(storage),
            leases,
            counters::nextTxnId,
        )
        val involutionCoordinator = InvolutionJobCoordinator(
            jobs,
            repo,
            leases,
            InvolutionExecutor(ledger, log, counters::nextSeq),
            Journal.forInvolution(storage),
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
            storage = storage,
            gate = CaptureGate(storage.ring),
        )

        // The storage half of the pipeline: drain the ring, apply a whole batch of captures as
        // one commit. Canceled with the plugin's scope, which is what stops it cleanly.
        drain = Drainer(
            storage = storage,
            ring = storage.ring,
            interning = storage.interning,
            sink = { deltas, epochMillis, cause, causedBy ->
                services.capture.record(deltas, epochMillis, cause, causedBy)
            },
            releaseSink = { from, to, epochMillis, cause, causedBy ->
                val flows = ledger.releaseFlows(from, to)
                if (flows.isNotEmpty()) services.capture.recordDirect(flows, epochMillis, cause, causedBy)
            },
        ).start(services.scope)

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
        if (::drain.isInitialized) drain.cancel()
        if (::services.isInitialized) services.scope.cancel()
        if (::storage.isInitialized) storage.close()
        logger.info("Tracel disabled.")
    }
}
