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
import com.tracel.storage.Storage
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.pending.SqlitePendingDeliveryRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Everything a listener or command needs to touch the ledger, wired once in [TracelPlugin.onEnable].
 *
 * Delegates [UnitOfWork] to [storage], so a listener that has several ledger calls to make writes
 * `services.atomically { ... }` and gets one hop onto the storage thread and one commit for the
 * lot of them, instead of one of each per call.
 */
class TracelServices(
    val repo: SqliteLotRepository,
    val ledger: LotLedger,
    val log: TransactionLog,
    val counters: SqliteCounters,
    val schedulers: TracelSchedulers,
    val scope: CoroutineScope,
    val rollback: RollbackJobCoordinator,
    val jobs: RollbackJobRepository,
    val undo: InvolutionJobCoordinator,
    val pendingDeliveries: SqlitePendingDeliveryRepository,
    val storage: Storage,
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
