package com.tracel.storage.support

import com.tracel.engine.capture.material.CaptureCoordinator
import com.tracel.engine.capture.material.flow.releaseFlows
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.capture.world.WorldCaptureCoordinator
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.capture.CaptureRing
import com.tracel.storage.capture.Drainer
import com.tracel.storage.codec.History
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.ports.container.ContainerSlotLog
import com.tracel.storage.ports.job.Journal
import com.tracel.storage.ports.job.RollbackJobRepository
import com.tracel.storage.ports.ledger.Leases
import com.tracel.storage.ports.ledger.LotRepository
import com.tracel.storage.ports.ledger.PendingDeliveryRepository
import com.tracel.storage.ports.log.TransactionLog
import com.tracel.storage.ports.log.WorldLog
import com.tracel.storage.ports.ops.Counters
import java.nio.file.Path

class Stack(
    path: Path,
    config: LsmConfig = LsmConfig(),
    ringSlots: Int = TracelStorage.DEFAULT_RING_SLOTS,
    overflowSlots: Int = ringSlots * CaptureRing.OVERFLOW_FACTOR,
) : AutoCloseable {
    val storage: TracelStorage =
        TracelStorage.open(path, ringSlots = ringSlots, overflowSlots = overflowSlots) { LsmEngine(it, History.configured(config)) }
    val counters: Counters = Counters(storage)
    val repo: LotRepository = LotRepository(storage, counters)
    val ledger: LotLedger = LotLedger(repo)
    val log: TransactionLog = TransactionLog(storage)
    val worldLog: WorldLog = WorldLog(storage)
    val containerSlots: ContainerSlotLog = ContainerSlotLog(storage, counters)
    val worldCapture: WorldCaptureCoordinator =
        WorldCaptureCoordinator(worldLog, counters::nextSeq, counters::nextSeqRange)
    val leases: Leases = Leases(storage)
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
