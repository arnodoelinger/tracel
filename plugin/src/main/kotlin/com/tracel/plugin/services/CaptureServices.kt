package com.tracel.plugin.services

import com.tracel.engine.capture.material.CaptureCoordinator
import com.tracel.engine.capture.material.SnapshotDiffer
import com.tracel.engine.capture.material.WearCapture
import com.tracel.engine.capture.world.EntityCaptureQueue
import com.tracel.engine.capture.world.WorldCaptureCoordinator
import com.tracel.plugin.bukkit.item.Experience
import com.tracel.plugin.capture.MaterialCapture
import com.tracel.plugin.capture.PendingCaptures
import com.tracel.plugin.capture.ShapeCapture

/** The write path of the log: what listeners hand over, and the queues that carry it to storage. */
class CaptureServices(services: TracelServices) {
    private val storage = services.storage

    val differ: SnapshotDiffer = SnapshotDiffer { holder ->
        storage.ledger.totalsAt(holder).filterKeys { !Experience.isExperience(it) }.mapValues { it.value.raw }
    }
    val shape: ShapeCapture = ShapeCapture(services)
    val material: MaterialCapture = MaterialCapture(services)
    val capture: CaptureCoordinator =
        CaptureCoordinator(storage.ledger, storage.log, storage.counters::nextTxnId, storage.counters::nextSeq)
    val wearCapture: WearCapture =
        WearCapture(storage.repo, storage.log, storage.wear, storage.counters::nextTxnId, storage.counters::nextSeq)
    val worldCapture: WorldCaptureCoordinator =
        WorldCaptureCoordinator(storage.worldLog, storage.counters::nextSeq, storage.counters::nextSeqRange)
    val entityCapture: EntityCaptureQueue =
        EntityCaptureQueue(worldCapture, storage.unit, waits = { storage.store.writesStopped != null })
    val pendingCaptures: PendingCaptures = PendingCaptures()
    var flushCapture: suspend () -> Boolean = { true }
}
