package com.tracel.plugin.services

import com.tracel.engine.actor.ActorFacts
import com.tracel.engine.capture.CaptureGate
import com.tracel.engine.capture.material.CaptureCoordinator
import com.tracel.engine.capture.material.SnapshotDiffer
import com.tracel.engine.capture.material.WearCapture
import com.tracel.engine.capture.world.EntityCaptureQueue
import com.tracel.engine.capture.world.WorldCaptureCoordinator
import com.tracel.engine.container.ContainerSlotLog
import com.tracel.engine.event.EventLog
import com.tracel.engine.foreign.ForeignHistory
import com.tracel.engine.ledger.ItemForms
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.PendingDeliveryRepository
import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.engine.log.RolledBack
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.job.record.RollbackJobRepository
import com.tracel.engine.rollback.journal.Journal
import com.tracel.engine.rollback.plan.WorldQuery
import com.tracel.engine.store.Counters
import com.tracel.engine.store.StoreAdmin
import com.tracel.engine.wear.WearLog
import com.tracel.engine.world.GroundPositions
import com.tracel.engine.world.WorldLog
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.platform.storage.UnitOfWork
import com.tracel.plugin.adapter.rollback.structure.rescue.rescueJoined
import com.tracel.plugin.adapter.world.playerIsOnline
import com.tracel.plugin.capture.MaterialCapture
import com.tracel.plugin.capture.PendingCaptures
import com.tracel.plugin.capture.ShapeCapture
import com.tracel.plugin.command.action.CoreProtectImportAction
import com.tracel.plugin.command.action.LookupAction
import com.tracel.plugin.command.action.support.PurgeGate
import com.tracel.plugin.config.*
import com.tracel.plugin.governor.GovernorSettings
import com.tracel.plugin.governor.TickGovernor
import com.tracel.plugin.listener.session.InspectorState
import com.tracel.plugin.listener.support.drop.BlockDrop
import com.tracel.plugin.listener.support.drop.BlockReleaseQueue
import com.tracel.plugin.listener.support.drop.HullDrop
import com.tracel.plugin.listener.support.guard.FreezeGuard
import com.tracel.plugin.listener.support.guard.SelfManagedWorldGuard
import com.tracel.plugin.listener.support.guard.SpawnGuard
import com.tracel.plugin.listener.support.redstone.RedstoneTrigger
import com.tracel.plugin.rollback.RollbackGenius
import com.tracel.plugin.rollback.composer.RollbackComposer
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.fluid.FluidFreeze
import com.tracel.plugin.specifics.block.warmFluidShapes
import com.tracel.plugin.status.rate.WriteRate
import com.tracel.plugin.util.whereabouts.EntityWhereabouts
import com.tracel.plugin.util.whereabouts.GroundWhereabouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private const val WHEREABOUTS_KEPT = 200_000

/**
 * Shared services used by `Tracel` listeners and commands.
 *
 * @param plugin plugin instance
 * @param repo stores the ledger lots
 * @param ledger tracks item ownership and balances
 * @param log stores transaction history
 * @param counters generates transaction and sequence IDs
 * @param schedulers provides plugin's schedulers
 * @param scope scope for plugin's background work
 * @param rollback coordinates rollback jobs
 * @param jobs stores rollback jobs
 * @param undo coordinates involution jobs
 * @param undoJournal which undo steps already ran, plus whether their items went back
 * @param pendingDeliveries stores pending item deliveries
 * @param unit the unit of work every write runs in
 * @param store what an operator can do to the stored history
 * @param gate fast path for capture events
 * @param worldLog stores world changes
 * @param containerSlots stores which slot a container's contents last sat in
 * @param wear stores how worn each tool was over time
 * @param actors stores a mob's type and a player's game mode
 * @param itemForms stores item metadata needed to reconstruct items
 * @param exportDirectory directory for history exports and imports
 * @param entityRestoreLimit how many entities one rollback may bring back before it asks
 * @param governorSettings how long a rollback may hold one region tick, at least and at most
 * @param logEntityDamage whether a blow a mob survives is written down as a change to it
 * @param paste where a lookup export goes
 * @param forwardCompatible server Minecraft is newer than the newest tested release
 * @param logging which kinds of history are written
 */
class TracelServices(
    val plugin: Plugin,
    val repo: LotRepository,
    val ledger: LotLedger,
    val log: TransactionLog,
    val counters: Counters,
    val schedulers: TracelSchedulers,
    val scope: CoroutineScope,
    val rollback: RollbackJobCoordinator,
    val jobs: RollbackJobRepository,
    val undo: InvolutionJobCoordinator,
    val undoJournal: Journal,
    val pendingDeliveries: PendingDeliveryRepository,
    val unit: UnitOfWork,
    val store: StoreAdmin,
    val gate: CaptureGate,
    val events: EventLog,
    val rolledBack: RolledBack,
    val foreign: ForeignHistory,
    val groundPositions: GroundPositions,
    val worldLog: WorldLog,
    val containerSlots: ContainerSlotLog,
    val wear: WearLog,
    val actors: ActorFacts,
    val itemForms: ItemForms,
    val exportDirectory: Path,
    val logEntityDamage: Boolean = DEFAULT_LOG_ENTITY_DAMAGE,
    val paste: PasteSettings = PasteSettings(),
    val forwardCompatible: Boolean = false,
    val logging: LoggingSettings = LoggingSettings(),

    governorSettings: GovernorSettings = GovernorSettings(),

    @Volatile
    var entityRestoreLimit: Int = DEFAULT_ENTITY_RESTORE_LIMIT,

    @Volatile
    var autoPurge: Job? = null,

    @Volatile
    var worldEdit: AutoCloseable? = null
) : UnitOfWork by unit {
    val purging: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    var purgeSettings: AutoPurgeSettings = AutoPurgeSettings()

    @Volatile
    var lastPurgeMillis: Long? = null
    val governor: TickGovernor = TickGovernor(plugin, governorSettings)
    val writeRate: WriteRate = WriteRate(total = { counters.seqIssued })
    val purgeGate: PurgeGate = PurgeGate { composite.isRunning }
    val differ: SnapshotDiffer = SnapshotDiffer { holder -> ledger.totalsAt(holder).mapValues { it.value.raw } }
    val shape: ShapeCapture = ShapeCapture(this)
    val material: MaterialCapture = MaterialCapture(this)
    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
    val wearCapture: WearCapture = WearCapture(repo, log, wear, counters::nextTxnId, counters::nextSeq)
    val worldCapture: WorldCaptureCoordinator =
        WorldCaptureCoordinator(worldLog, counters::nextSeq, counters::nextSeqRange)
    val entityCapture: EntityCaptureQueue = EntityCaptureQueue(worldCapture, unit)
    val restorer: MaterialRestorer = MaterialRestorer(this)
    val selfManagedWorld: SelfManagedWorldGuard = SelfManagedWorldGuard()
    val structureRestorer: StructureRestorer = StructureRestorer(this)
    val composite: RollbackGenius = RollbackComposer(this, structureRestorer, restorer, restorer)
    val redstoneTriggers: RedstoneTrigger = RedstoneTrigger()
    val whereabouts: EntityWhereabouts = EntityWhereabouts(capacity = WHEREABOUTS_KEPT)
    val groundWhereabouts: GroundWhereabouts = GroundWhereabouts(groundPositions)
    val selfManagedSpawns: SpawnGuard = SpawnGuard()
    val blockDrops: BlockDrop = BlockDrop()
    val hullDrops: HullDrop = HullDrop()
    val blockReleases: BlockReleaseQueue = BlockReleaseQueue(this)
    val inspectors: InspectorState = InspectorState()
    val lookup: LookupAction by lazy { LookupAction(this) }
    val coreProtectImport: CoreProtectImportAction by lazy { CoreProtectImportAction(this) }
    val pendingCaptures: PendingCaptures = PendingCaptures()
    val frozen: FreezeGuard = FreezeGuard()
    internal val fluidFreeze: FluidFreeze = FluidFreeze()
    val worldQuery: WorldQuery = WorldQuery(::playerIsOnline)
    var flushCapture: suspend () -> Boolean = { true }

    /** Tables the rollback path would otherwise build on a region thread the first time it needs them. */
    fun warmRollback() = warmFluidShapes()

    /** Checks a player who logged out inside what a rollback has since written. */
    suspend fun rescueJoined(player: Player) = structureRestorer.rescueJoined(player)
}
