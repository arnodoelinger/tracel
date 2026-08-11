package com.tracel.plugin

import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.capture.ShadowDiffer
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.model.id.TxnId
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.ledger.SqliteLotRepository
import kotlinx.coroutines.CoroutineScope

/** Everything a listener or command needs to touch the ledger, wired once in [TracelPlugin.onEnable]. */
class TracelServices(
    val repo: SqliteLotRepository,
    val ledger: LotLedger,
    val log: TransactionLog,
    val counters: SqliteCounters,
    val schedulers: TracelSchedulers,
    val scope: CoroutineScope,
) {
    val differ: ShadowDiffer = ShadowDiffer()

    fun nextTxn(): TxnId = counters.nextTxnId()

    val capture: CaptureCoordinator = CaptureCoordinator(ledger, log, counters::nextTxnId, counters::nextSeq)
}
