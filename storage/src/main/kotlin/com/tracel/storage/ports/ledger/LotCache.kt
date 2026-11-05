package com.tracel.storage.ports.ledger

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.model.holder.HolderId
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.LotRecord
import com.tracel.storage.ffm.Key
import com.tracel.storage.intern.Interning
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicLong

/** What the lot repository remembers between units. */
internal class LotCache(private val interning: Interning) {
    private val lots: Cache<LotId, Lot> = Caffeine.newBuilder().maximumSize(100_000).build()
    private val version = AtomicLong()

    private val evictedFloor = AtomicLong()

    private val stamps: Cache<LotId, Long> = Caffeine.newBuilder()
        .maximumSize(2_000_000)
        .executor(Runnable::run)
        .removalListener<LotId, Long> { _, stamp, cause ->
            if (cause.wasEvicted() && stamp != null) evictedFloor.accumulateAndGet(
                stamp,
                ::maxOf
            )
        }
        .build()

    /** Moves on every committed change. */
    fun version(): Long = version.get()

    /**
     * Whether any of [lots] changed after [witness].
     *
     * A lot whose stamp was evicted counts as changed at the newest eviction.
     */
    fun changedSince(lots: Collection<LotId>, witness: Long): Boolean {
        val floor = evictedFloor.get()
        return lots.any { (stamps.getIfPresent(it) ?: floor) > witness }
    }

    /** Stamps [lots] as changed, once [unit] has committed. */
    fun changed(unit: StorageUnit, vararg lots: Long) = unit.afterCommit {
        val at = version.incrementAndGet()
        for (lot in lots) stamps.put(LotId(lot), at)
    }

    /** The cached lot, without reading anything. */
    fun cached(id: LotId): Lot? = lots.getIfPresent(id)

    /** Caches [lot]; if [unit] wrote it, until [unit] turns out never to land. */
    fun put(unit: StorageUnit, id: LotId, lot: Lot) {
        lots.put(id, lot)
        if (unit.batch.touches(Key(Tag.LOT[id.raw]))) unit.afterAbort { lots.invalidate(id) }
    }

    /** The lot with [id], from the cache or read through [unit]. Throws if there is none. */
    fun read(unit: StorageUnit, id: LotId): Lot {
        lots.getIfPresent(id)?.let { return it }
        val value = unit.get(Tag.LOT[id.raw]) ?: error("lot $id does not exist.")
        return decode(unit, id, value).also { put(unit, id, it) }
    }

    /** Decodes a lot from [value] read through [unit]. */
    fun decode(unit: StorageUnit, id: LotId, value: MemorySegment): Lot = Lot(
        id,
        interning.resolveItemKey(unit, LotRecord.itemKeyId[value]),
        Quantity(LotRecord.quantity[value]),
        TxnId(LotRecord.createdBy[value]),
    )

    /** Slot [i] of [pack] at [holder], its lot read through the cache. */
    fun accountLot(unit: StorageUnit, holder: HolderId, pack: Pack, i: Int): AccountLot = AccountLot(
        holder,
        read(unit, LotId(pack.lots[i])),
        Quantity(pack.remaining[i]),
        Seq(pack.fifo[i]),
    )

    /**
     * Forgets every cached lot, for a store that was replaced under it.
     *
     * Stamps stay: they only ever grow.
     */
    fun forget() {
        lots.invalidateAll()
    }
}
