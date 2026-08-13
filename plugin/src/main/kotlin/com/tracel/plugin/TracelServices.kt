package com.tracel.plugin

import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.capture.ShadowDiffer
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.InvolutionJobCoordinator
import com.tracel.engine.rollback.RollbackJobCoordinator
import com.tracel.engine.rollback.RollbackJobRepository
import com.tracel.model.id.TxnId
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.plugin.listener.ExplosionDropCorrelator
import com.tracel.plugin.listener.RedstoneTriggerTracker
import com.tracel.plugin.listener.SelfManagedSpawnGuard
import com.tracel.plugin.listener.VanillaAssumptionGuard
import com.tracel.plugin.rollback.PhysicalRestorer
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.pending.SqlitePendingDeliveryRepository
import kotlinx.coroutines.CoroutineScope

/** Everything a listener or command needs to touch the ledger, wired once in [TracelPlugin.onEnable]. */
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
) {
    val differ: ShadowDiffer = ShadowDiffer()

    fun nextTxn(): TxnId = counters.nextTxnId()

    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
    val restorer: PhysicalRestorer = PhysicalRestorer(this)
    val redstoneTriggers: RedstoneTriggerTracker = RedstoneTriggerTracker()
    val selfManagedSpawns: SelfManagedSpawnGuard = SelfManagedSpawnGuard()
    val explosionDrops: ExplosionDropCorrelator = ExplosionDropCorrelator()
    val vanillaAssumptions: VanillaAssumptionGuard = VanillaAssumptionGuard()
}
