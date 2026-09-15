package com.tracel.plugin

import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.capture.SnapshotDiffer
import com.tracel.engine.container.ContainerSlotLog
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.job.RollbackJobRepository
import com.tracel.engine.world.EntityCaptureQueue
import com.tracel.engine.world.WorldCaptureCoordinator
import com.tracel.engine.world.WorldLog
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.platform.storage.UnitOfWork
import com.tracel.plugin.adapter.world.playerIsOnline
import com.tracel.plugin.listener.session.InspectorState
import com.tracel.plugin.listener.MaterialCapture
import com.tracel.plugin.listener.ShapeCapture
import com.tracel.plugin.listener.support.BlockDropCorrelator
import com.tracel.plugin.listener.support.HullDropCorrelator
import com.tracel.plugin.listener.support.BlockReleaseQueue
import com.tracel.plugin.listener.support.RedstoneTriggerTracker
import com.tracel.plugin.listener.support.SelfManagedSpawnGuard
import com.tracel.plugin.rollback.composer.RollbackComposer
import com.tracel.plugin.rollback.RollbackGenius
import com.tracel.plugin.listener.support.FrozenHolders
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.listener.support.SelfManagedWorldGuard
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.util.EntityWhereabouts
import com.tracel.plugin.util.GroundWhereabouts
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.ports.ledger.ItemForms
import com.tracel.storage.ports.ledger.LotRepository
import com.tracel.storage.ports.ledger.PendingDeliveryRepository
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.ports.world.GroundPositions
import kotlinx.coroutines.CoroutineScope
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import com.tracel.engine.rollback.plan.WorldQuery

/**
 * Shared services used by `Tracel` listeners and commands.
 *
 * @param plugin plugin instance.
 * @param repo stores the ledger lots.
 * @param ledger tracks item ownership and balances.
 * @param log stores transaction history.
 * @param counters generates transaction and sequence IDs.
 * @param schedulers provides plugin's schedulers.
 * @param scope scope for plugin's background work.
 * @param rollback coordinates rollback jobs.
 * @param jobs stores rollback jobs.
 * @param undo coordinates involution jobs.
 * @param pendingDeliveries stores pending item deliveries.
 * @param storage provides access to plugin's storage.
 * @param gate fast path for capture events.
 * @param worldLog stores world changes.
 * @param containerSlots stores which slot a container's contents last sat in.
 * @param itemForms stores item metadata needed to reconstruct items.
 * @param exportDirectory directory for history exports and imports.
 * @param entityRestoreLimit how many entities one rollback may bring back before it asks.
 * @param logEntityDamage whether a blow a mob survives is written down as a change to it.
 * @param forwardCompatible server Minecraft is newer than the newest tested release.
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
    val pendingDeliveries: PendingDeliveryRepository,
    val storage: TracelStorage,
    val gate: CaptureGate,
    val worldLog: WorldLog,
    val containerSlots: ContainerSlotLog,
    val itemForms: ItemForms,
    val exportDirectory: Path,
    val entityRestoreLimit: Int = DEFAULT_ENTITY_RESTORE_LIMIT,
    val logEntityDamage: Boolean = DEFAULT_LOG_ENTITY_DAMAGE,
    val forwardCompatible: Boolean = false,
) : UnitOfWork by storage {
    val differ: SnapshotDiffer = SnapshotDiffer { holder -> ledger.totalsAt(holder).mapValues { it.value.raw } }
    val shape: ShapeCapture = ShapeCapture(this)
    val material: MaterialCapture = MaterialCapture(this)
    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
    val worldCapture: WorldCaptureCoordinator = WorldCaptureCoordinator(worldLog, counters::nextSeq, counters::nextSeqRange)
    val entityCapture: EntityCaptureQueue = EntityCaptureQueue(worldCapture, storage)
    val restorer: MaterialRestorer = MaterialRestorer(this)
    val selfManagedWorld: SelfManagedWorldGuard = SelfManagedWorldGuard()
    val structureRestorer: StructureRestorer = StructureRestorer(this)
    val composite: RollbackGenius = RollbackComposer(this, structureRestorer, restorer, restorer)
    val redstoneTriggers: RedstoneTriggerTracker = RedstoneTriggerTracker()
    val whereabouts: EntityWhereabouts = EntityWhereabouts()
    val groundWhereabouts: GroundWhereabouts = GroundWhereabouts(GroundPositions(storage))
    val selfManagedSpawns: SelfManagedSpawnGuard = SelfManagedSpawnGuard()
    val blockDrops: BlockDropCorrelator = BlockDropCorrelator()
    val hullDrops: HullDropCorrelator = HullDropCorrelator()
    val blockReleases: BlockReleaseQueue = BlockReleaseQueue(this)
    val inspectors: InspectorState = InspectorState()
    val pendingCaptures: PendingCaptures = PendingCaptures()
    val frozen: FrozenHolders = FrozenHolders()
    val worldQuery: WorldQuery = WorldQuery(::playerIsOnline)
    var flushCapture: suspend () -> Boolean = { true }
}
