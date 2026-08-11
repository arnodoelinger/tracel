package com.tracel.plugin

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.id.TxnId
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.storage.ledger.SqliteLotRepository
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/** Everything a listener or command needs to touch the ledger, wired once in [TracelPlugin.onEnable]. */
class TracelServices(
    val repo: SqliteLotRepository,
    val ledger: LotLedger,
    val schedulers: TracelSchedulers,
    val scope: CoroutineScope,
) {
    private val nextTxnRaw = AtomicLong(1)

    fun nextTxn(): TxnId = TxnId(nextTxnRaw.getAndIncrement())
}
