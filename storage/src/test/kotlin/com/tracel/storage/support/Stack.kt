package com.tracel.storage.support

import com.tracel.engine.capture.material.CaptureCoordinator
import com.tracel.engine.capture.material.flow.releaseFlows
import com.tracel.engine.capture.world.WorldCaptureCoordinator
import com.tracel.engine.ledger.LotLedger
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.Lot
import com.tracel.storage.TracelStorage
import com.tracel.storage.admin.StoredCounters
import com.tracel.storage.capture.CaptureDrainer
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.capture.CaptureRing
import com.tracel.storage.codec.History
import com.tracel.storage.history.StoredContainerSlotLog
import com.tracel.storage.history.StoredTransactionLog
import com.tracel.storage.history.StoredWorldLog
import com.tracel.storage.ledger.StoredLotRepository
import com.tracel.storage.ledger.StoredPendingDeliveryRepository
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.LsmOptions
import com.tracel.storage.rollback.StoredJournal
import com.tracel.storage.rollback.StoredLeases
import com.tracel.storage.rollback.StoredRollbackJobRepository
import java.nio.file.Path

class Stack(
    path: Path,
    config: LsmOptions = LsmOptions(),
    ringSlots: Int = TracelStorage.DEFAULT_RING_SLOTS,
    overflowSlots: Int = ringSlots * CaptureRing.OVERFLOW_FACTOR,
) : AutoCloseable {
    val storage: TracelStorage =
        TracelStorage.open(path, ringSlots = ringSlots, overflowSlots = overflowSlots) {
            LsmEngine(
                it,
                History.configured(config)
            )
        }
    val counters: StoredCounters = StoredCounters(storage)
    val repo: StoredLotRepository = StoredLotRepository(storage, counters)
    val ledger: LotLedger = LotLedger(repo)
    val log: StoredTransactionLog = StoredTransactionLog(storage)
    val worldLog: StoredWorldLog = StoredWorldLog(storage)
    val containerSlots: StoredContainerSlotLog = StoredContainerSlotLog(storage, counters)
    val worldCapture: WorldCaptureCoordinator =
        WorldCaptureCoordinator(worldLog, counters::nextSeq, counters::nextSeqRange)
    val leases: StoredLeases = StoredLeases(storage)
    val jobs: StoredRollbackJobRepository = StoredRollbackJobRepository(storage)
    val journal: StoredJournal = StoredJournal.forRollback(storage)
    val involutionJournal: StoredJournal = StoredJournal.forInvolution(storage)
    val pending: StoredPendingDeliveryRepository = StoredPendingDeliveryRepository(storage, counters)
    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
    val gate: CaptureGate = CaptureGate(storage.ring)

    val drainer: CaptureDrainer = CaptureDrainer(
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

    suspend fun mint(at: HolderId, item: ItemKey, amount: Long): Lot =
        ledger.mint(at, item, Quantity(amount), counters.nextTxnId())

    suspend fun move(from: HolderId, to: HolderId, item: ItemKey, amount: Long) {
        ledger.move(from, to, item, Quantity(amount), counters.nextTxnId())
    }

    suspend fun burn(at: HolderId, item: ItemKey, amount: Long, reason: SinkKind = SinkKind.HAZARD) {
        ledger.burn(at, item, Quantity(amount), reason, counters.nextTxnId())
    }

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
