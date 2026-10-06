package com.tracel.plugin.startup

import com.tracel.engine.capture.material.flow.releaseFlows
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.involution.apply.InvolutionExecutor
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.plan.WorldQuery
import com.tracel.platform.Versions
import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.actor.ActorWrites
import com.tracel.plugin.actor.EntityKinds
import com.tracel.plugin.actor.PlayerModes
import com.tracel.plugin.actor.PlayerSessions
import com.tracel.plugin.adapter.item.PendingItemForms
import com.tracel.plugin.adapter.world.playerIsOnline
import com.tracel.plugin.command.args.scope.ScopeLimits
import com.tracel.plugin.command.suggest.order.CommandOrderListener
import com.tracel.plugin.command.tree.TracelCommand
import com.tracel.plugin.config.migrate.FileVersions
import com.tracel.plugin.config.migrate.TomlMigrator
import com.tracel.plugin.config.read.readSettings
import com.tracel.plugin.i18n.Messages
import com.tracel.plugin.integration.worldedit.WorldEditAttachListener
import com.tracel.plugin.integration.worldedit.WorldEditSupport
import com.tracel.plugin.listener.listenersOf
import com.tracel.plugin.listener.registerObserved
import com.tracel.plugin.listener.support.flow.ignoranceIsPermanent
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.metrics.TracelMetrics
import com.tracel.plugin.scheduler.TracelSchedulers
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.setup.SetupListener
import com.tracel.plugin.setup.SetupState
import com.tracel.plugin.startup.version.MinecraftVersion
import com.tracel.plugin.status.disk.DiskGuard
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.capture.Drainer
import com.tracel.storage.ports.actor.ActorFacts
import com.tracel.storage.ports.container.ContainerSlotLog
import com.tracel.storage.ports.event.EventLog
import com.tracel.storage.ports.job.Journal
import com.tracel.storage.ports.job.RollbackJobRepository
import com.tracel.storage.ports.ledger.ItemForms
import com.tracel.storage.ports.ledger.Leases
import com.tracel.storage.ports.ledger.LotRepository
import com.tracel.storage.ports.ledger.PendingDeliveryRepository
import com.tracel.storage.ports.log.RolledBack
import com.tracel.storage.ports.log.TransactionLog
import com.tracel.storage.ports.log.WorldLog
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.ports.ops.ForeignHistory
import com.tracel.storage.ports.ops.StoreAdmin
import com.tracel.storage.ports.wear.WearLog
import com.tracel.storage.ports.world.GroundPositions
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.tomlj.Toml

private const val LAST_CAPTURE_WAIT_MILLIS = 500L
private const val WRITE_RATE_SAMPLE_MILLIS = 5_000L

/** Run startup sequence. */
internal fun enableTracel(plugin: TracelPlugin): TracelRuntime {
    // Check supported Minecraft versions
    val forwardCompatible = MinecraftVersion.check(plugin.server.minecraftVersion, plugin)

    // Config
    plugin.dataFolder.mkdirs()

    // No room to write history: the server does not start. Nothing is opened before this
    DiskGuard.requireRoom(plugin.dataFolder.resolve("database"))
    val configFile = plugin.dataFolder.resolve("config.toml")
    val firstRun = !configFile.exists()
    if (firstRun) plugin.saveResource("config.toml", false)
    TomlMigrator.migrateFile(
        configFile.toPath(), Versions.Format.CONFIG, FileVersions.CONFIG_STEPS, plugin.logger, announce = !firstRun,
    )
    val setup = SetupState(plugin.dataFolder.toPath())
    if (firstRun) setup.begin()
    val config = Toml.parse(configFile.toPath())
    config.errors().forEach { plugin.logger.severe(it.toString()) }
    Messages.load(plugin)

    // Settings, storage services, etc.
    val settings = readSettings(
        config.getTable("advanced"),
        config.getTable("rollback"),
        complain = { plugin.logger.severe(it) },
        paste = config.getTable("paste"),
        purge = config.getTable("purge"),
        logging = config.getTable("logging"),
    )
    ScopeLimits.rollbackMaxBlocks = settings.rollbackMaxRadius
    val storage = TracelStorage.open(plugin.dataFolder.resolve("database").toPath(), settings.store)
    resumeImport(plugin, storage)
    migrateStore(plugin, storage)
    val entityKinds = EntityKinds()
    storage.interning.entityKinds = entityKinds
    val schedulers = TracelSchedulers(plugin, storage.dispatcher)
    val counters = Counters(storage)
    val repo = LotRepository(storage, counters)
    val ledger = LotLedger(repo)
    val log = TransactionLog(storage)
    val worldLog = WorldLog(storage)
    val containerSlots = ContainerSlotLog(storage, counters)
    val wear = WearLog(storage, counters)
    val actors = ActorFacts(storage)
    val events = EventLog(storage)
    val admin = StoreAdmin(storage)
    val leases = Leases(storage)
    val jobs = RollbackJobRepository(storage)
    val pendingDeliveries = PendingDeliveryRepository(storage, counters)
    val journalExecutor = JournalExecutor(
        RollbackExecutor(ledger, log, counters::nextSeq),
        Journal.forRollback(storage),
        leases,
        counters::nextTxnId,
    )
    val undoJournal = Journal.forInvolution(storage)
    val involutionCoordinator = InvolutionJobCoordinator(
        jobs,
        repo,
        leases,
        InvolutionExecutor(ledger, log, counters::nextSeq),
        undoJournal,
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
        rollback = RollbackJobCoordinator(
            repo,
            WorldQuery(::playerIsOnline),
            leases,
            journalExecutor,
            jobs,
            ledgerVersion = repo::version,
            changedSince = repo::changedSince
        ),
        jobs = jobs,
        undo = involutionCoordinator,
        undoJournal = undoJournal,
        pendingDeliveries = pendingDeliveries,
        unit = storage,
        store = admin,
        gate = CaptureGate(storage.ring),
        events = events,
        rolledBack = RolledBack(storage),
        foreign = ForeignHistory(storage, worldLog, log, events, counters),
        groundPositions = GroundPositions(storage),
        worldLog = worldLog,
        containerSlots = containerSlots,
        wear = wear,
        actors = actors,
        itemForms = ItemForms(storage),
        exportDirectory = plugin.dataFolder.resolve("database").resolve("exports").toPath(),
        entityRestoreLimit = settings.entityRestoreLimit,
        governorSettings = settings.governor,
        logEntityDamage = settings.logEntityDamage,
        paste = settings.paste,
        forwardCompatible = forwardCompatible,
        logging = settings.logging,
    )
    Bukkit.getAsyncScheduler().runNow(plugin) { services.warmRollback() }
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
        rawWorldSink = { raw -> worldLog.appendRaw(raw, counters::nextSeqRange) },
        placedSink = { placed ->
            services.capture.record(
                placed.deltas,
                placed.epochMillis,
                placed.cause,
                placed.causedBy,
                placed.at,
                ::ignoranceIsPermanent
            )
        },
    )
    services.scope.launch {
        val freed = leases.reapAbandoned(System.currentTimeMillis(), 0L)
        if (freed.isNotEmpty()) {
            plugin.logger.warning("released lot leases of ${freed.size} rollback job(s) that did not finish before shutdown: ${freed.joinToString { it.raw.toString() }}")
        }
    }

    val drain = drainer.start(services.scope)
    plugin.haltIfCaptureDies(drain, "capture drain")

    services.flushCapture = {
        coroutineScope {
            val entities = async { services.entityCapture.flush() }
            val first = services.pendingCaptures.await()
            services.blockReleases.flush()
            val drained = drainer.drainThrough()
            val second = services.pendingCaptures.await()
            val settled = entities.await() && services.entityCapture.flush()
            plugin.writeItemForms(services)
            services.groundWhereabouts.flush()
            first && second && settled && drained
        }
    }

    val lastCaptures: suspend () -> Unit = {
        services.pendingCaptures.await(LAST_CAPTURE_WAIT_MILLIS)
        services.blockReleases.flush()
        drainer.drainThrough()
        services.entityCapture.flush(LAST_CAPTURE_WAIT_MILLIS)
        plugin.writeItemForms(services)
        services.groundWhereabouts.flush()
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
    registerObserved(CommandOrderListener(), plugin)
    registerObserved(entityKinds, plugin)
    val actorWrites = ActorWrites(services.scope)
    val modes = PlayerModes(actorWrites, actors)
    registerObserved(modes, plugin)
    modes.noteOnline()
    registerObserved(PlayerSessions(actorWrites, actors), plugin)
    plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
        TracelCommand.register(event.registrar(), services)
    }

    DiskGuard.start(plugin, services, plugin.dataFolder.resolve("database"))
    services.purgeSettings = settings.autoPurge
    services.autoPurge = startAutoPurge(plugin, services, settings.autoPurge)
    services.scope.launch {
        while (isActive) {
            services.writeRate.sample()
            Telemetry.backlog(admin.capture.backlog)
            delay(WRITE_RATE_SAMPLE_MILLIS.milliseconds)
        }
    }

    if (settings.logging.blocks && settings.logging.worldEdit) {
        WorldEditSupport.attach(services)
        registerObserved(WorldEditAttachListener(services), plugin)
    }

    if (setup.pending) {
        registerObserved(SetupListener(services, setup), plugin)
    }

    TracelMetrics.start(plugin, services, settings)

    plugin.logger.info("Tracel ${plugin.pluginMeta.version} enabled.")

    return TracelRuntime(admin, services, drain, entityDrain, formDrain, releaseDrain, lastCaptures)
}
