package com.tracel.storage.ports.ledger

import com.tracel.engine.ledger.repository.Lots
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.LotRecord
import com.tracel.storage.intern.Interning
import com.tracel.storage.ports.log.QueryProbe
import com.tracel.storage.ports.log.walkWanted
import com.tracel.storage.ports.ops.Counters
import java.util.Arrays

/** The lots themselves, read through the [LotCache] that every part of the repository shares. */
internal class StoredLots(
    private val storage: TracelStorage,
    private val counters: Counters,
    private val cache: LotCache,
) : Lots {
    private val interning: Interning get() = storage.interning

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        val id = counters.nextLotId()
        return storage.write {
            val itemKeyId = interning.internItemKey(this, itemKey)
            put(Tag.LOT[id.raw], LotRecord.of(itemKeyId, quantity.raw, createdBy.raw))
            cache.changed(this, id.raw)
            Lot(id, itemKey, quantity, createdBy).also { cache.put(this, id, it) }
        }
    }

    override suspend fun lot(id: LotId): Lot = cache.cached(id) ?: storage.read { cache.read(this, id) }

    override suspend fun prefetchLots(ids: Collection<LotId>) {
        if (ids.isEmpty()) return
        val missing = ArrayList<LotId>()
        for (id in ids) if (cache.cached(id) == null) missing += id
        if (missing.isEmpty()) return
        val sorted = rawsOf(missing)
        storage.read {
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Tag.LOT, sorted, 0, sorted.size) { at, cursor ->
                val id = LotId(at)
                cache.put(this, id, cache.decode(this, id, cursor.value()))
            }
        }
    }

    override suspend fun lotsOfAll(ids: Collection<LotId>): Map<LotId, Lot> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, Lot>(ids.size)
        val missing = ArrayList<LotId>()
        for (id in ids.distinct()) {
            val cached = cache.cached(id)
            if (cached != null) out[id] = cached else missing += id
        }
        if (missing.isEmpty()) return out
        val sorted = rawsOf(missing)
        return storage.read {
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Tag.LOT, sorted, 0, sorted.size) { at, cursor ->
                val id = LotId(at)
                out[id] = cache.decode(this, id, cursor.value()).also { cache.put(this, id, it) }
            }
            for (id in missing) if (id !in out) out[id] = cache.read(this, id)
            out
        }
    }
}

/** [ids] as raw longs, ascending, which is the order every keyed walk wants them in. */
internal fun rawsOf(ids: List<LotId>): LongArray =
    LongArray(ids.size) { ids[it].raw }.also { Arrays.sort(it) }
