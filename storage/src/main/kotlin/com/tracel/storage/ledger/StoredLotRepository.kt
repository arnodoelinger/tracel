package com.tracel.storage.ledger

import com.tracel.engine.ledger.repository.*
import com.tracel.model.lot.LotId
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.TracelStorage
import com.tracel.storage.admin.StoredCounters

/** A [LotRepository] over a [TracelStorage] and its [UnitOfWork], with a cache of [Lots] and [LotEdges]. */
class StoredLotRepository private constructor(
    private val storage: TracelStorage,
    private val parts: Parts,
) : LotRepository,
    UnitOfWork by storage,
    Lots by parts.lots,
    LotEdges by parts.edges,
    AccountQueues by parts.queues,
    Placements by parts.placements {
    constructor(storage: TracelStorage, counters: StoredCounters) : this(storage, Parts(storage, counters))

    override suspend fun version(): Long = parts.cache.version()

    override suspend fun changedSince(lots: Collection<LotId>, witness: Long): Boolean =
        parts.cache.changedSince(lots, witness)

    init {
        storage.afterReplace(::forget)
    }

    fun forget() {
        parts.cache.forget()
    }

    private class Parts(storage: TracelStorage, counters: StoredCounters) {
        val cache = LotCache(storage.interning)
        val packs = LotPacks(counters)
        val lots = StoredLots(storage, counters, cache)
        val edges = StoredLotEdges(storage, cache)
        val queues = StoredAccountQueues(storage, counters, cache, edges, packs)
        val placements = StoredPlacements(storage, counters, cache, packs)
    }
}
