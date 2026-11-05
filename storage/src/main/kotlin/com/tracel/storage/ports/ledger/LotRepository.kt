package com.tracel.storage.ports.ledger

import com.tracel.engine.ledger.repository.AccountQueues
import com.tracel.engine.ledger.repository.LotEdges
import com.tracel.engine.ledger.repository.Lots
import com.tracel.engine.ledger.repository.Placements
import com.tracel.model.lot.LotId
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.TracelStorage
import com.tracel.storage.ports.ops.Counters
import com.tracel.engine.ledger.repository.LotRepository as LotRepositoryPort

/** A [LotRepositoryPort] over a [TracelStorage] and its [UnitOfWork], with a cache of [Lots] and [LotEdges]. */
class LotRepository private constructor(
    private val storage: TracelStorage,
    private val parts: Parts,
) : LotRepositoryPort,
    UnitOfWork by storage,
    Lots by parts.lots,
    LotEdges by parts.edges,
    AccountQueues by parts.queues,
    Placements by parts.placements {
    constructor(storage: TracelStorage, counters: Counters) : this(storage, Parts(storage, counters))

    override suspend fun version(): Long = parts.cache.version()

    override suspend fun changedSince(lots: Collection<LotId>, witness: Long): Boolean =
        parts.cache.changedSince(lots, witness)

    init {
        storage.afterReplace(::forget)
    }

    fun forget() {
        parts.cache.forget()
    }

    private class Parts(storage: TracelStorage, counters: Counters) {
        val cache = LotCache(storage.interning)
        val packs = Packs(counters)
        val lots = StoredLots(storage, counters, cache)
        val edges = StoredLotEdges(storage, cache)
        val queues = StoredAccountQueues(storage, counters, cache, edges, packs)
        val placements = StoredPlacements(storage, counters, cache, packs)
    }
}
