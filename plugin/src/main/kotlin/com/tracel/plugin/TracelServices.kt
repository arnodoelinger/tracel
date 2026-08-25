package com.tracel.plugin

import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.capture.SnapshotDiffer
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.RollbackJobCoordinator
import com.tracel.engine.rollback.RollbackJobRepository
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.model.id.TxnId
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.platform.storage.UnitOfWork
import com.tracel.plugin.inspect.InspectorState
import com.tracel.plugin.listener.capture.SelfManagedSpawnGuard
import com.tracel.plugin.listener.capture.VanillaAssumptionGuard
import com.tracel.plugin.listener.explosion.ExplosionDropCorrelator
import com.tracel.plugin.listener.redstone.RedstoneTriggerTracker
import com.tracel.plugin.rollback.PhysicalRestorer
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.ports.Counters
import com.tracel.storage.ports.LotRepository
import com.tracel.storage.ports.PendingDeliveryRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Everything a listener or command needs to touch the ledger, wired once in [TracelPlugin.onEnable].
 *
 * Two doors, and which one a caller uses is the whole design:
 *
 * [gate] is the region-thread door. It interns two IDs, writes 24 bytes into an off-heap ring
 * and returns.
 *
 * [atomically] is the storage-thread door, for commands, rollbacks, and the handful of captures
 * that genuinely have to read the ledger before they know what happened. It delegates to
 * [storage], so several ledger calls become one hop and one commit instead of one of each.
 */
class TracelServices(
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
) : UnitOfWork by storage {
    val differ: SnapshotDiffer = SnapshotDiffer { holder -> ledger.totalsAt(holder).mapValues { it.value.raw } }

    suspend fun nextTxn(): TxnId = counters.nextTxnId()

    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
    val restorer: PhysicalRestorer = PhysicalRestorer(this)
    val redstoneTriggers: RedstoneTriggerTracker = RedstoneTriggerTracker()
    val selfManagedSpawns: SelfManagedSpawnGuard = SelfManagedSpawnGuard()
    val explosionDrops: ExplosionDropCorrelator = ExplosionDropCorrelator()
    val vanillaAssumptions: VanillaAssumptionGuard = VanillaAssumptionGuard()
    val inspectors: InspectorState = InspectorState()
}
