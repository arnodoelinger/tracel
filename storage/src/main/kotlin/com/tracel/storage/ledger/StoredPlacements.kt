package com.tracel.storage.ledger

import com.tracel.engine.ledger.LotPortion
import com.tracel.engine.ledger.repository.PlacedRun
import com.tracel.engine.ledger.repository.PlacedRuns
import com.tracel.engine.ledger.repository.Placements
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.LotId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.admin.StoredCounters
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.PackSum
import com.tracel.storage.codec.Scalar
import com.tracel.storage.codec.Tag
import com.tracel.storage.history.QueryProbe
import com.tracel.storage.history.walkWanted
import com.tracel.storage.Interning
import com.tracel.storage.util.eachRow
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet

/**
 * Where lots sit right now, kept as packs: each [LotPack] is a run of one holder's lots of
 * one item key, in queue order, and every move rewrites whole packs rather than lot rows.
 */
internal class StoredPlacements(
    private val storage: TracelStorage,
    private val counters: StoredCounters,
    private val cache: LotCache,
    private val packs: LotPacks,
) : Placements {
    private val interning: Interning get() = storage.interning

    override suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot? = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read null
        val pack = packs.of(this, lotId.raw)?.takeIf { it.holderId == holderId } ?: return@read null
        val i = pack.indexOf(lotId.raw)
        if (i < 0) null else cache.accountLot(this, holder, pack, i)
    }

    override suspend fun census(itemKey: ItemKey): Long = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read 0L
        var total = 0L
        eachRow(Tag.PACK_ITEM[itemKeyId]) { cursor ->
            when (interning.resolveHolder(this, KeyReader.u32(cursor.key(), 5))) {
                is HolderId.Source, is HolderId.Sink -> return@eachRow
                else -> total += PackSum.sum[cursor.value()]
            }
        }
        total
    }

    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val slots = ArrayList<Pair<Int, Long>>()
        eachRow(Tag.PACK_ITEM[itemKeyId]) { cursor ->
            slots += KeyReader.u32(cursor.key(), 5) to KeyReader.u64(cursor.key(), 9)
        }
        val out = ArrayList<AccountLot>()
        for ((holderId, tail) in slots) {
            val slot = get(Tag.PACK_AT[holderId, itemKeyId, tail]) ?: continue
            val pack = packs.decode(holderId, itemKeyId, slot)
            val holder = interning.resolveHolder(this, holderId)
            for (i in 0 until pack.size) out += cache.accountLot(this, holder, pack, i)
        }
        out
    }

    override suspend fun placementsAt(holder: HolderId): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        eachRow(Tag.PACK_AT[holderId]) { cursor ->
            val pack = packs.decode(holderId, KeyReader.u32(cursor.key(), 5), cursor.value())
            for (i in 0 until pack.size) out += cache.accountLot(this, holder, pack, i)
        }
        out
    }

    override suspend fun currentHolderOf(lotId: LotId): HolderId? = storage.read {
        val pointer = get(Tag.LOT_PACK[lotId.raw]) ?: return@read null
        packs.holderIdOf(this, Scalar.asLong(pointer))?.let { interning.resolveHolder(this, it) }
    }

    override suspend fun currentHoldersOf(ids: Collection<LotId>): Map<LotId, HolderId> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val out = HashMap<LotId, HolderId>(unique.size)
            val holderOfPack = Long2ObjectOpenHashMap<HolderId>()
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Tag.LOT_PACK, sorted, 0, sorted.size) { at, cursor ->
                val packId = Scalar.asLong(cursor.value())
                var holder = holderOfPack.get(packId)
                if (holder == null) {
                    holder = packs.holderIdOf(this, packId)?.let { interning.resolveHolder(this, it) }
                    if (holder != null) holderOfPack.put(packId, holder)
                }
                if (holder != null) out[LotId(at)] = holder
            }
            out
        }
    }

    override suspend fun placedRuns(roots: Collection<LotId>): PlacedRuns {
        if (roots.isEmpty()) return PlacedRuns(emptyList(), emptyList())
        val unique = roots.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val edged = LongOpenHashSet()
            walkWanted(Tag.EDGE_FROM, sorted, 0, sorted.size) { at, _ -> edged.add(at) }
            val byPack = Long2ObjectLinkedOpenHashMap<LongOpenHashSet>()
            walkWanted(Tag.LOT_PACK, sorted, 0, sorted.size) { at, cursor ->
                if (at !in edged) {
                    val packId = Scalar.asLong(cursor.value())
                    var chosen = byPack.get(packId)
                    if (chosen == null) {
                        chosen = LongOpenHashSet()
                        byPack.put(packId, chosen)
                    }
                    chosen.add(at)
                }
            }
            val runs = ArrayList<PlacedRun>(byPack.size)
            val taken = LongOpenHashSet()
            for (entry in byPack.long2ObjectEntrySet()) {
                val packId = entry.longKey
                val chosen = entry.value
                val pack = packs.read(this, packId) ?: continue
                val lots = LongArray(chosen.size)
                val quantities = LongArray(chosen.size)
                var n = 0
                for (i in 0 until pack.size) if (pack.lots[i] in chosen) {
                    lots[n] = pack.lots[i]
                    quantities[n] = pack.remaining[i]
                    n++
                }
                if (n == 0) continue
                runs += PlacedRun(interning.resolveHolder(this, pack.holderId), lots.copyOf(n), quantities.copyOf(n))
                for (k in 0 until n) taken.add(lots[k])
            }
            PlacedRuns(runs, unique.filter { it.raw !in taken })
        }
    }

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot = storage.write {
        val lot = cache.read(this, lotId)
        notPlaced(this, lotId)
        val holderId = interning.internHolder(this, holder)
        val itemKeyId = interning.internItemKey(this, lot.itemKey)
        val fifo = counters.nextFifoSeqOn(this)
        packs.insert(this, holderId, itemKeyId, longArrayOf(lotId.raw), longArrayOf(quantity.raw), longArrayOf(fifo))
        addToTotal(this, holderId, itemKeyId, quantity.raw)
        cache.changed(this, lotId.raw)
        AccountLot(holder, lot, quantity, Seq(fifo))
    }

    override suspend fun placeAll(holder: HolderId, portions: List<LotPortion>) {
        if (portions.isEmpty()) return
        storage.write {
            val holderId = interning.internHolder(this, holder)
            val seen = LongOpenHashSet(portions.size)
            val byItem = Int2ObjectLinkedOpenHashMap<Entries>()
            for ((lotId, quantity) in portions) {
                check(seen.add(lotId.raw)) { "lot $lotId is already placed at $holder." }
                notPlaced(this, lotId)
                val itemKeyId = interning.internItemKey(this, cache.read(this, lotId).itemKey)
                var entries = byItem.get(itemKeyId)
                if (entries == null) {
                    entries = Entries()
                    byItem.put(itemKeyId, entries)
                }
                entries.add(lotId.raw, quantity.raw, counters.nextFifoSeqOn(this))
            }
            for (entry in byItem.int2ObjectEntrySet()) {
                val itemKeyId = entry.intKey
                val entries = entry.value
                packs.insert(this, holderId, itemKeyId, entries.lots(), entries.remaining(), entries.fifo())
                addToTotal(this, holderId, itemKeyId, entries.sum)
            }
            cache.changed(this, *seen.toLongArray())
        }
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: return@write
            val pack = packs.of(this, lotId.raw)?.takeIf { it.holderId == holderId } ?: return@write
            val i = pack.indexOf(lotId.raw)
            if (i < 0) return@write
            packs.swap(this, pack, pack.keeping(BooleanArray(pack.size) { it != i }))
            delete(Tag.LOT_PACK[lotId.raw])
            addToTotal(this, holderId, pack.itemKeyId, -pack.remaining[i])
            cache.changed(this, lotId.raw)
        }
    }

    override suspend fun rehome(from: HolderId, to: HolderId, lotId: LotId): Quantity =
        rehomeAll(from, to, listOf(lotId)).getValue(lotId)

    override suspend fun rehomeAll(from: HolderId, to: HolderId, lotIds: List<LotId>): Map<LotId, Quantity> {
        if (lotIds.isEmpty()) return emptyMap()
        return storage.write {
            val fromId = interning.findHolderId(this, from)
                ?: error("lot ${lotIds.first()} is not currently placed at $from.")
            val sorted = rawsOf(lotIds)
            val packOf = Long2LongOpenHashMap(lotIds.size).apply { defaultReturnValue(NO_PACK) }
            walkWanted(Tag.LOT_PACK, sorted, 0, sorted.size) { at, cursor ->
                packOf.put(at, Scalar.asLong(cursor.value()))
            }
            val byPack = Long2ObjectLinkedOpenHashMap<LongArrayList>()
            for (lotId in lotIds) {
                val packId = packOf.get(lotId.raw)
                if (packId == NO_PACK) error("lot $lotId is not currently placed at $from.")
                var chosen = byPack.get(packId)
                if (chosen == null) {
                    chosen = LongArrayList()
                    byPack.put(packId, chosen)
                }
                chosen.add(lotId.raw)
            }
            val toId = if (from == to) fromId else interning.internHolder(this, to)
            val moved = HashMap<LotId, Quantity>(lotIds.size * 2)
            val delta = Int2LongOpenHashMap()
            for (entry in byPack.long2ObjectEntrySet()) {
                val packId = entry.longKey
                val chosen = entry.value
                val pack = packs.read(this, packId)?.takeIf { it.holderId == fromId }
                    ?: error("lot ${LotId(chosen.getLong(0))} is not currently placed at $from.")
                val take = BooleanArray(pack.size)
                var sum = 0L
                for (lot in chosen.toLongArray()) {
                    val i = pack.indexOf(lot)
                    check(i >= 0) { "lot ${LotId(lot)} is not currently placed at $from." }
                    take[i] = true
                    moved[LotId(lot)] = Quantity(pack.remaining[i])
                    sum += pack.remaining[i]
                }
                if (toId == fromId) continue
                if (take.all { it }) {
                    packs.move(this, pack, toId)
                } else {
                    packs.swap(this, pack, pack.keeping(BooleanArray(pack.size) { !take[it] }))
                    val chosenPack = pack.keeping(take)!!
                    packs.insert(this, toId, pack.itemKeyId, chosenPack.lots, chosenPack.remaining, chosenPack.fifo)
                }
                delta.addTo(pack.itemKeyId, sum)
            }
            for (entry in delta.int2LongEntrySet()) {
                addToTotal(this, fromId, entry.intKey, -entry.longValue)
                addToTotal(this, toId, entry.intKey, entry.longValue)
            }
            if (toId != fromId) cache.changed(this, *LongArray(lotIds.size) { lotIds[it].raw })
            moved
        }
    }

    override suspend fun relocate(from: HolderId, to: HolderId) {
        storage.write {
            val fromId = interning.findHolderId(this, from) ?: return@write
            val toId = interning.internHolder(this, to)
            if (fromId == toId) return@write

            // Read the whole account out before touching any of it: rewriting keys under a cursor
            // that is still walking the same prefix is a good way to visit a key twice or not at all.
            val moving = ArrayList<LotPack>()
            eachRow(Tag.PACK_AT[fromId]) { cursor ->
                moving += packs.decode(fromId, KeyReader.u32(cursor.key(), 5), cursor.value())
            }
            val lots = LongArrayList()
            for (pack in moving) {
                packs.move(this, pack, toId)
                addToTotal(this, fromId, pack.itemKeyId, -pack.sum)
                addToTotal(this, toId, pack.itemKeyId, pack.sum)
                for (lot in pack.lots) lots.add(lot)
            }
            if (lots.isNotEmpty()) cache.changed(this, *lots.toLongArray())
        }
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: error("no placement of $retiredLotId at $holder.")
            val pack = packs.of(this, retiredLotId.raw)?.takeIf { it.holderId == holderId }
                ?: error("no placement of $retiredLotId at $holder.")
            val i = pack.indexOf(retiredLotId.raw)
            check(i >= 0) { "no placement of $retiredLotId at $holder." }
            val previous = pack.remaining[i]

            // The replacement keeps the retired lot's queue slot: for everyone who comes after,
            // it is still the oldest thing in this account.
            val lots = pack.lots.copyOf().also { it[i] = newLotId.raw }
            val left = pack.remaining.copyOf().also { it[i] = remaining.raw }
            packs.swap(this, pack, LotPack(pack.id, holderId, pack.itemKeyId, lots, left, pack.fifo))
            delete(Tag.LOT_PACK[retiredLotId.raw])
            put(Tag.LOT_PACK[newLotId.raw], Scalar.long(pack.id))
            addToTotal(this, holderId, pack.itemKeyId, remaining.raw - previous)
            cache.changed(this, retiredLotId.raw, newLotId.raw)
        }
    }

    private fun notPlaced(unit: StorageUnit, lotId: LotId) {
        val pointer = unit.get(Tag.LOT_PACK[lotId.raw]) ?: return
        val holderId = packs.holderIdOf(unit, Scalar.asLong(pointer)) ?: return
        error("lot $lotId is already placed at ${interning.resolveHolder(unit, holderId)}.")
    }

    private class Entries {
        private val lots = LongArrayList()
        private val remaining = LongArrayList()
        private val fifo = LongArrayList()
        var sum = 0L
            private set

        fun add(lot: Long, left: Long, at: Long) {
            lots.add(lot)
            remaining.add(left)
            fifo.add(at)
            sum += left
        }

        fun lots(): LongArray = lots.toLongArray()
        fun remaining(): LongArray = remaining.toLongArray()
        fun fifo(): LongArray = fifo.toLongArray()
    }

    private companion object {
        const val NO_PACK = Long.MIN_VALUE
    }
}
