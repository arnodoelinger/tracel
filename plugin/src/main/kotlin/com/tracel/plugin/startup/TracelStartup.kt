package com.tracel.plugin.startup

import com.tracel.engine.capture.releaseFlows
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.plan.WorldQuery
import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.PendingItemForms
import com.tracel.plugin.adapter.world.playerIsOnline
import com.tracel.plugin.command.TracelCommand
import com.tracel.plugin.listener.api.registerObserved
import com.tracel.plugin.listener.listenersOf
import com.tracel.plugin.listener.support.ignoranceIsPermanent
import com.tracel.plugin.readSettings
import com.tracel.plugin.scheduler.TracelSchedulers
import com.tracel.plugin.startup.version.MinecraftVersion
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.capture.Drainer
import com.tracel.storage.ports.container.ContainerSlotLog
import com.tracel.storage.ports.job.Journal
import com.tracel.storage.ports.job.RollbackJobRepository
import com.tracel.storage.ports.ledger.ItemForms
import com.tracel.storage.ports.ledger.LotLeaseRegistry
import com.tracel.storage.ports.ledger.LotRepository
import com.tracel.storage.ports.ledger.PendingDeliveryRepository
import com.tracel.storage.ports.log.TransactionLog
import com.tracel.storage.ports.log.WorldLog
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.ports.wear.WearLog
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tomlj.Toml

/** Wired plugin after a successful [enable]. */
internal class TracelRuntime(
    val storage: TracelStorage,
    val services: TracelServices,
    val drain: Job,
    val entityDrain: Job,
    val formDrain: Job,
    val releaseDrain: Job,
)

/** Run startup sequence. */
internal fun enableTracel(plugin: TracelPlugin): TracelRuntime {
    // Check supported Minecraft versions
    val forwardCompatible = MinecraftVersion.check(plugin.server.minecraftVersion, plugin)

    // Config
    plugin.dataFolder.mkdirs()
    val configFile = plugin.dataFolder.resolve("config.toml")
    if (!configFile.exists()) plugin.saveResource("config.toml", false)
    val config = Toml.parse(configFile.toPath())
    config.errors().forEach { plugin.logger.severe(it.toString()) }

    // Settings, storage services, etc.
    val settings = readSettings(
        config.getTable("storage"),
        config.getTable("rollback"),
        complain = { plugin.logger.severe(it) },
    )
    val storage = TracelStorage.open(
        plugin.dataFolder.resolve("database").toPath(),
        ringSlots = settings.ringSlots,
        lsm = settings.lsm,
    )
    val schedulers = TracelSchedulers(plugin, storage.dispatcher)
    val counters = Counters(storage)
    val repo = LotRepository(storage, counters)
    val ledger = LotLedger(repo)
    val log = TransactionLog(storage)
    val worldLog = WorldLog(storage)
    val containerSlots = ContainerSlotLog(storage, counters)
    val wear = WearLog(storage, counters)
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
    val services = TracelServices(
        plugin = plugin,
        repo = repo,
        ledger = ledger,
        log = log,
        counters = counters,
        schedulers = schedulers,
        scope = CoroutineScope(SupervisorJob() + schedulers.async + plugin.captureFailures()),
        rollback = RollbackJobCoordinator(repo, WorldQuery(::playerIsOnline), leases, journalExecutor, jobs, ledgerVersion = counters::peekTxnId),
        jobs = jobs,
        undo = involutionCoordinator,
        pendingDeliveries = pendingDeliveries,
        storage = storage,
        gate = CaptureGate(storage.ring),
        worldLog = worldLog,
        containerSlots = containerSlots,
        wear = wear,
        itemForms = ItemForms(storage),
        exportDirectory = plugin.dataFolder.resolve("database").resolve("exports").toPath(),
        entityRestoreLimit = settings.entityRestoreLimit,
        logEntityDamage = settings.logEntityDamage,
        forwardCompatible = forwardCompatible,
    )
    val drainer = Drainer(
        storage = storage,
        ring = storage.ring,
        interning = storage.interning,
        sink = { deltas, epochMillis, cause, causedBy ->
            services.capture.record(deltas, epochMillis, cause, causedBy, mintShortfall = ::ignoranceIsPermanent)
        },
        releaseSink = { from, to, epochMillis, cause, causedBy ->
            val flows = ledger.releaseFlows(from, to)
            if (flows.isNotEmpty()) services.capture.recordDirect(flows, epochMillis, cause, causedBy)
        },
        worldSink = { edits -> services.worldCapture.record(edits) },
    )
    val drain = drainer.start(services.scope)
    plugin.haltIfCaptureDies(drain, "capture drain")

    services.flushCapture = {
        val first = services.pendingCaptures.await()
        services.blockReleases.flush()
        drainer.drainOnce()
        val second = services.pendingCaptures.await()
        val entities = services.entityCapture.flush()
        plugin.writeItemForms(services)
        services.groundWhereabouts.flush()
        first && second && entities
    }

    val entityDrain = services.entityCapture.start(services.scope)
    plugin.haltIfCaptureDies(entityDrain, "entity drain")

    val releaseDrain = services.blockReleases.start(services.scope)
    plugin.haltIfCaptureDies(releaseDrain, "release drain")

    PendingItemForms.reset()
    val formDrain = services.scope.launch {
        try {
            while (isActive) {
                plugin.writeItemForms(services)
                services.groundWhereabouts.flush()
                PendingItemForms.awaitWork(5_000L)
            }
        } finally {
            withContext(NonCancellable) {
                plugin.writeItemForms(services)
                services.groundWhereabouts.flush()
            }
        }
    }
    plugin.haltIfCaptureDies(formDrain, "item-form drain")

    for (listener in listenersOf(services)) {
        val handlers = registerObserved(listener, plugin)
        check(handlers > 0) {
            "${listener.javaClass.simpleName} is in Listeners.kt with no @Observes handler on it"
        }
    }
    plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
        TracelCommand.register(event.registrar(), services)
    }

    // Cancel all jobs
    Job().cancel()

    plugin.logger.info("Tracel ${plugin.pluginMeta.version} enabled.")

    return TracelRuntime(storage, services, drain, entityDrain, formDrain, releaseDrain)
}
