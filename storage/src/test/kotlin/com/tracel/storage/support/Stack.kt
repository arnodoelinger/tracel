package com.tracel.storage.support

import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.capture.releaseFlows
import com.tracel.engine.world.WorldCaptureCoordinator
import com.tracel.engine.ledger.LotLedger
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.capture.Drainer
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.ports.Counters
import com.tracel.storage.ports.Journal
import com.tracel.storage.ports.LotLeaseRegistry
import com.tracel.storage.ports.LotRepository
import com.tracel.storage.ports.RollbackJobRepository
import com.tracel.storage.ports.TransactionLog
import com.tracel.storage.ports.WorldLog
import com.tracel.storage.ports.PendingDeliveryRepository
import java.nio.file.Path

class Stack(path: Path, config: LsmConfig = LsmConfig()) : AutoCloseable {
    val storage: TracelStorage = TracelStorage.open(path) { LsmEngine(it, config) }
    val counters: Counters = Counters(storage)
    val repo: LotRepository = LotRepository(storage, counters)
    val ledger: LotLedger = LotLedger(repo)
    val log: TransactionLog = TransactionLog(storage)
    val worldLog: WorldLog = WorldLog(storage)
    val worldCapture: WorldCaptureCoordinator = WorldCaptureCoordinator(worldLog, counters::nextSeq)
    val leases: LotLeaseRegistry = LotLeaseRegistry(storage)
    val jobs: RollbackJobRepository = RollbackJobRepository(storage)
    val journal: Journal = Journal.forRollback(storage)
    val involutionJournal: Journal = Journal.forInvolution(storage)
    val pending: PendingDeliveryRepository = PendingDeliveryRepository(storage, counters)
    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
    val gate: CaptureGate = CaptureGate(storage.ring)

    val drainer: Drainer = Drainer(
        storage = storage,
        ring = storage.ring,
        interning = storage.interning,
        sink = { deltas, epochMillis, cause, causedBy -> capture.record(deltas, epochMillis, cause, causedBy) },
        releaseSink = { from, to, epochMillis, cause, causedBy ->
            val flows = ledger.releaseFlows(from, to)
            if (flows.isNotEmpty()) capture.recordDirect(flows, epochMillis, cause, causedBy)
        },
        worldSink = { edits -> worldCapture.record(edits) },
    )

    suspend fun drain(): Int {
        var total = 0
        while (true) {
            val drained = drainer.drainOnce()
            if (drained == 0) return total
            total += drained
        }
    }

    override fun close() {
        storage.close()
    }
}
