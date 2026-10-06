package com.tracel.storage.ports.ledger

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.annotations.Consume
import com.tracel.engine.ledger.LotPortion
import com.tracel.engine.ledger.repository.PlacedRun
import com.tracel.engine.ledger.repository.PlacedRuns
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ffm.Key
import com.tracel.storage.intern.Interning
import com.tracel.storage.ports.log.QueryProbe
import com.tracel.storage.ports.log.walkWanted
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicLong
import com.tracel.engine.ledger.repository.LotRepository as LotRepositoryPort
import com.tracel.storage.codec.records.Lot as LotRecord

/** [LotRepositoryPort] over the packed keyspace. */
class LotRepository(
    private val storage: TracelStorage,
    private val counters: Counters,
) : LotRepositoryPort, UnitOfWork by storage {
    private val interning: Interning get() = storage.interning
    private val lots: Cache<LotId, Lot> = Caffeine.newBuilder().maximumSize(100_000).build()
    private val packs = Packs(counters)
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

    override suspend fun version(): Long = version.get()

    override suspend fun changedSince(lots: Collection<LotId>, witness: Long): Boolean {
        val floor = evictedFloor.get()
        return lots.any { (stamps.getIfPresent(it) ?: floor) > witness }
    }

    private fun StorageUnit.changed(vararg lots: Long) = afterCommit {
        val at = version.incrementAndGet()
        for (lot in lots) stamps.put(LotId(lot), at)
    }

    private fun StorageUnit.cache(id: LotId, lot: Lot) {
        lots.put(id, lot)
        if (batch.touches(Key(Keys.lot(id.raw)))) afterAbort { lots.invalidate(id) }
    }

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        val id = counters.nextLotId()
        return storage.write {
            val itemKeyId = interning.internItemKey(this, itemKey)
            put(Keys.lot(id.raw), Records.lot(itemKeyId, quantity.raw, createdBy.raw))
            changed(id.raw)
            Lot(id, itemKey, quantity, createdBy).also { cache(id, it) }
        }
    }

    override suspend fun lot(id: LotId): Lot = lots.getIfPresent(id) ?: storage.read { readLot(this, id) }

    override suspend fun recordEdge(edge: LotEdge) {
        storage.write {
            val packed = when (edge) {
                is LotEdge.Split -> Records.edge(Records.EDGE_SPLIT, edge.quantity.raw, 0, 0)
                is LotEdge.Transform -> Records.edge(
                    Records.EDGE_TRANSFORM,
                    edge.quantity.raw,
                    edge.craftedBy.raw,
                    interning.internHolder(this, edge.producedAt),
                )

                is LotEdge.Compensate -> Records.edge(
                    Records.EDGE_COMPENSATE,
                    edge.quantity.raw,
                    edge.rollbackJob.raw,
                    0,
                )
            }
            put(Keys.edgeFrom(edge.parent.raw, edge.child.raw), packed)
            put(Keys.edgeInto(edge.child.raw, edge.parent.raw), packed)
            changed(edge.parent.raw, edge.child.raw)
        }
    }

    override suspend fun removeEdge(parent: LotId, child: LotId) {
        storage.write {
            delete(Keys.edgeFrom(parent.raw, child.raw))
            delete(Keys.edgeInto(child.raw, parent.raw))
            changed(parent.raw, child.raw)
        }
    }

    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = storage.read {
        readEdgesFrom(lotId)
    }

    override suspend fun findCompensateEdge(originalLotId: LotId, job: RollbackJobId): LotEdge.Compensate? =
        storage.read {
            eachRow(Keys.edgeFromPrefix(originalLotId.raw)) { cursor ->
                val value = cursor.value()
                if (Records.edgeKind(value) != Records.EDGE_COMPENSATE) return@eachRow
                if (Records.edgeReference(value) != job.raw) return@eachRow
                return@read LotEdge.Compensate(
                    LotId(KeyReader.u64(cursor.key(), 9)),
                    originalLotId,
                    Quantity(Records.edgeQuantity(value)),
                    job,
                )
            }
            null
        }

    override suspend fun edgesFromAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val out = HashMap<LotId, MutableList<LotEdge>>(unique.size)
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Keys.EDGE_FROM, sorted, 0, sorted.size, Keys::edgeFromPrefix) { at, cursor ->
                out.getOrPut(LotId(at)) { ArrayList() } +=
                    decodeEdge(this, cursor.value(), parent = at, child = cursor.keyU64(9))
            }
            for (id in unique) out.putIfAbsent(id, ArrayList(0))
            out
        }
    }

    override suspend fun edgesIntoAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val out = HashMap<LotId, MutableList<LotEdge>>(unique.size)
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Keys.EDGE_INTO, sorted, 0, sorted.size, Keys::edgeIntoPrefix) { at, cursor ->
                out.getOrPut(LotId(at)) { ArrayList() } +=
                    decodeEdge(this, cursor.value(), parent = cursor.keyU64(9), child = at)
            }
            for (id in unique) out.putIfAbsent(id, ArrayList(0))
            out
        }
    }

    override suspend fun prefetchLots(ids: Collection<LotId>) {
        if (ids.isEmpty()) return
        val missing = ArrayList<LotId>()
        for (id in ids) if (lots.getIfPresent(id) == null) missing += id
        if (missing.isEmpty()) return
        val sorted = rawsOf(missing)
        storage.read {
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Keys.LOT, sorted, 0, sorted.size, Keys::lot) { at, cursor ->
                val id = LotId(at)
                cache(id, decodeLot(this, id, cursor.value()))
            }
        }
    }

    override suspend fun lotsOfAll(ids: Collection<LotId>): Map<LotId, Lot> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, Lot>(ids.size)
        val missing = ArrayList<LotId>()
        for (id in ids.distinct()) {
            val cached = lots.getIfPresent(id)
            if (cached != null) out[id] = cached else missing += id
        }
        if (missing.isEmpty()) return out
        val sorted = rawsOf(missing)
        return storage.read {
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Keys.LOT, sorted, 0, sorted.size, Keys::lot) { at, cursor ->
                val id = LotId(at)
                out[id] = decodeLot(this, id, cursor.value()).also { cache(id, it) }
            }
            for (id in missing) if (id !in out) out[id] = readLot(this, id)
            out
        }
    }

    override suspend fun currentHoldersOf(ids: Collection<LotId>): Map<LotId, HolderId> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val out = HashMap<LotId, HolderId>(unique.size)
            val holderOfPack = HashMap<Long, HolderId?>()
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Keys.LOT_PACK, sorted, 0, sorted.size, Keys::lotPack) { at, cursor ->
                val packId = Records.asLong(cursor.value())
                val holder = holderOfPack.getOrPut(packId) {
                    packs.holderIdOf(this, packId)?.let { interning.resolveHolder(this, it) }
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
            val edged = HashSet<Long>()
            walkWanted(Keys.EDGE_FROM, sorted, 0, sorted.size, Keys::edgeFromPrefix) { at, _ -> edged += at }
            val byPack = LinkedHashMap<Long, HashSet<Long>>()
            walkWanted(Keys.LOT_PACK, sorted, 0, sorted.size, Keys::lotPack) { at, cursor ->
                if (at !in edged) byPack.getOrPut(Records.asLong(cursor.value())) { HashSet() } += at
            }
            val runs = ArrayList<PlacedRun>(byPack.size)
            val taken = HashSet<Long>()
            for ((packId, chosen) in byPack) {
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
                for (k in 0 until n) taken += lots[k]
            }
            PlacedRuns(runs, unique.filter { it.raw !in taken })
        }
    }

    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        scan(Keys.packAtPrefix(holderId, itemKeyId)).use { cursor ->
            while (out.size < limit && cursor.next()) {
                val pack = packs.decode(holderId, itemKeyId, cursor.value())
                var i = 0
                while (i < pack.size && out.size < limit) out += accountLot(this, holder, pack, i++)
            }
        }
        out
    }

    @Consume
    override suspend fun takeFifo(
        holder: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): List<LotPortion> = storage.write {
        val holderId = interning.findHolderId(this, holder)
            ?: error("insufficient balance at $holder for $itemKey: needed ${quantity.raw}, have 0")
        val itemKeyId = interning.findItemKeyId(this, itemKey)
            ?: error("insufficient balance at $holder for $itemKey: needed ${quantity.raw}, have 0")
        consume(this, holder, holderId, itemKey, itemKeyId, longArrayOf(quantity.raw), strict = true, txn)[0]
    }

    @Consume
    override suspend fun drainFifo(
        holder: HolderId,
        itemKey: ItemKey,
        owed: List<Pair<HolderId, Long>>,
        txn: TxnId,
    ): List<Pair<HolderId, List<LotPortion>>> = storage.write {
        if (owed.isEmpty()) return@write emptyList()
        val holderId = interning.findHolderId(this, holder) ?: return@write emptyList()
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@write emptyList()
        val wants = LongArray(owed.size) { owed[it].second }
        val taken = consume(this, holder, holderId, itemKey, itemKeyId, wants, strict = false, txn)
        owed.indices.filter { taken[it].isNotEmpty() }.map { owed[it].first to taken[it] }
    }

    private fun consume(
        unit: StorageUnit,
        holder: HolderId,
        holderId: Int,
        itemKey: ItemKey,
        itemKeyId: Int,
        wants: LongArray,
        strict: Boolean,
        txn: TxnId,
    ): Array<ArrayList<LotPortion>> {
        val want = wants.sum()
        val queue = ArrayList<Pack>()
        var have = 0L
        unit.scan(Keys.packAtPrefix(holderId, itemKeyId)).use { cursor ->
            while (have < want && cursor.next()) {
                val pack = packs.decode(holderId, itemKeyId, cursor.value())
                queue += pack
                have += pack.sum
            }
        }
        check(!strict || have >= want) {
            "insufficient balance at $holder for $itemKey: needed $want, short by ${want - have}"
        }

        val out = Array(wants.size) { ArrayList<LotPortion>(4) }
        var d = 0
        var still = wants[0]
        var spent = 0L
        val touched = ArrayList<Long>()
        var done = false
        for (pack in queue) {
            if (done) break
            val keep = BooleanArray(pack.size) { true }
            var lots: LongArray? = null
            var remaining: LongArray? = null
            for (i in 0 until pack.size) {
                var lot = pack.lots[i]
                var left = pack.remaining[i]
                touched += lot
                while (left > 0L) {
                    while (still == 0L && d + 1 < wants.size) still = wants[++d]
                    if (still == 0L) {
                        done = true
                        break
                    }
                    if (left <= still) {
                        keep[i] = false
                        unit.delete(Keys.lotPack(lot))
                        out[d] += LotPortion(LotId(lot), Quantity(left))
                        still -= left
                        spent += left
                        left = 0L
                    } else {
                        val split = split(unit, itemKey, itemKeyId, lot, left, still, txn)
                        lots = lots ?: pack.lots.copyOf()
                        remaining = remaining ?: pack.remaining.copyOf()
                        lots[i] = split.keptLotId
                        remaining[i] = split.keptRemaining
                        unit.delete(Keys.lotPack(lot))
                        unit.put(Keys.lotPack(split.keptLotId), Records.long(pack.id))
                        out[d] += split.taken
                        spent += still
                        left = split.keptRemaining
                        lot = split.keptLotId
                        still = 0L
                    }
                }
                if (done) break
            }
            if (keep.all { it } && lots == null) continue
            val edited = if (lots == null) pack else Pack(pack.id, holderId, itemKeyId, lots, remaining!!, pack.fifo)
            packs.swap(unit, pack, edited.keeping(keep))
        }
        addToTotal(unit, holderId, itemKeyId, -spent)
        if (touched.isNotEmpty()) unit.changed(*touched.toLongArray())
        return out
    }

    override suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read 0L
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read 0L
        get(Keys.total(holderId, itemKeyId))?.let(Records::asLong) ?: 0L
    }

    override suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyMap()
        val out = LinkedHashMap<ItemKey, Long>()
        eachRow(Keys.totalPrefix(holderId)) { cursor ->
            val total = Records.asLong(cursor.value())
            if (total > 0) out[interning.resolveItemKey(this, KeyReader.u32(cursor.key(), 5))] = total
        }
        out
    }

    override suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot? = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read null
        val pack = packs.of(this, lotId.raw)?.takeIf { it.holderId == holderId } ?: return@read null
        val i = pack.indexOf(lotId.raw)
        if (i < 0) null else accountLot(this, holder, pack, i)
    }

    override suspend fun census(itemKey: ItemKey): Long = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read 0L
        var total = 0L
        eachRow(Keys.packItemPrefix(itemKeyId)) { cursor ->
            when (interning.resolveHolder(this, KeyReader.u32(cursor.key(), 5))) {
                is HolderId.Source, is HolderId.Sink -> return@eachRow
                else -> total += LotRecord.sumOf(cursor.value())
            }
        }
        total
    }

    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val slots = ArrayList<Pair<Int, Long>>()
        eachRow(Keys.packItemPrefix(itemKeyId)) { cursor ->
            slots += KeyReader.u32(cursor.key(), 5) to KeyReader.u64(cursor.key(), 9)
        }
        val out = ArrayList<AccountLot>()
        for ((holderId, tail) in slots) {
            val slot = get(Keys.packAt(holderId, itemKeyId, tail)) ?: continue
            val pack = packs.decode(holderId, itemKeyId, slot)
            val holder = interning.resolveHolder(this, holderId)
            for (i in 0 until pack.size) out += accountLot(this, holder, pack, i)
        }
        out
    }

    override suspend fun placementsAt(holder: HolderId): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        eachRow(Keys.packAtHolderPrefix(holderId)) { cursor ->
            val pack = packs.decode(holderId, KeyReader.u32(cursor.key(), 5), cursor.value())
            for (i in 0 until pack.size) out += accountLot(this, holder, pack, i)
        }
        out
    }

    override suspend fun currentHolderOf(lotId: LotId): HolderId? = storage.read {
        val pointer = get(Keys.lotPack(lotId.raw)) ?: return@read null
        packs.holderIdOf(this, Records.asLong(pointer))?.let { interning.resolveHolder(this, it) }
    }

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot = storage.write {
        val lot = readLot(this, lotId)
        notPlaced(this, lotId)
        val holderId = interning.internHolder(this, holder)
        val itemKeyId = interning.internItemKey(this, lot.itemKey)
        val fifo = counters.nextFifoSeqOn(this)
        packs.insert(this, holderId, itemKeyId, longArrayOf(lotId.raw), longArrayOf(quantity.raw), longArrayOf(fifo))
        addToTotal(this, holderId, itemKeyId, quantity.raw)
        changed(lotId.raw)
        AccountLot(holder, lot, quantity, Seq(fifo))
    }

    override suspend fun placeAll(holder: HolderId, portions: List<LotPortion>) {
        if (portions.isEmpty()) return
        storage.write {
            val holderId = interning.internHolder(this, holder)
            val seen = HashSet<Long>(portions.size * 2)
            val byItem = LinkedHashMap<Int, Entries>()
            for ((lotId, quantity) in portions) {
                check(seen.add(lotId.raw)) { "lot $lotId is already placed at $holder" }
                notPlaced(this, lotId)
                val itemKeyId = interning.internItemKey(this, readLot(this, lotId).itemKey)
                byItem.getOrPut(itemKeyId) { Entries() }.add(lotId.raw, quantity.raw, counters.nextFifoSeqOn(this))
            }
            for ((itemKeyId, entries) in byItem) {
                packs.insert(this, holderId, itemKeyId, entries.lots(), entries.remaining(), entries.fifo())
                addToTotal(this, holderId, itemKeyId, entries.sum)
            }
            changed(*seen.toLongArray())
        }
    }

    private fun notPlaced(unit: StorageUnit, lotId: LotId) {
        val pointer = unit.get(Keys.lotPack(lotId.raw)) ?: return
        val holderId = packs.holderIdOf(unit, Records.asLong(pointer)) ?: return
        error("lot $lotId is already placed at ${interning.resolveHolder(unit, holderId)}")
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: return@write
            val pack = packs.of(this, lotId.raw)?.takeIf { it.holderId == holderId } ?: return@write
            val i = pack.indexOf(lotId.raw)
            if (i < 0) return@write
            packs.swap(this, pack, pack.keeping(BooleanArray(pack.size) { it != i }))
            delete(Keys.lotPack(lotId.raw))
            addToTotal(this, holderId, pack.itemKeyId, -pack.remaining[i])
            changed(lotId.raw)
        }
    }

    override suspend fun rehome(from: HolderId, to: HolderId, lotId: LotId): Quantity =
        rehomeAll(from, to, listOf(lotId)).getValue(lotId)

    override suspend fun rehomeAll(from: HolderId, to: HolderId, lotIds: List<LotId>): Map<LotId, Quantity> {
        if (lotIds.isEmpty()) return emptyMap()
        return storage.write {
            val fromId = interning.findHolderId(this, from)
                ?: error("lot ${lotIds.first()} is not currently placed at $from")
            val sorted = rawsOf(lotIds)
            val packOf = HashMap<Long, Long>(lotIds.size * 2)
            walkWanted(Keys.LOT_PACK, sorted, 0, sorted.size, Keys::lotPack) { at, cursor ->
                packOf[at] = Records.asLong(cursor.value())
            }
            val byPack = LinkedHashMap<Long, ArrayList<Long>>()
            for (lotId in lotIds) {
                val packId = packOf[lotId.raw] ?: error("lot $lotId is not currently placed at $from")
                byPack.getOrPut(packId) { ArrayList() } += lotId.raw
            }
            val toId = if (from == to) fromId else interning.internHolder(this, to)
            val moved = HashMap<LotId, Quantity>(lotIds.size * 2)
            val delta = HashMap<Int, Long>()
            for ((packId, chosen) in byPack) {
                val pack = packs.read(this, packId)?.takeIf { it.holderId == fromId }
                    ?: error("lot ${LotId(chosen.first())} is not currently placed at $from")
                val take = BooleanArray(pack.size)
                var sum = 0L
                for (lot in chosen) {
                    val i = pack.indexOf(lot)
                    check(i >= 0) { "lot ${LotId(lot)} is not currently placed at $from" }
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
                delta.merge(pack.itemKeyId, sum, Long::plus)
            }
            for ((itemKeyId, sum) in delta) {
                addToTotal(this, fromId, itemKeyId, -sum)
                addToTotal(this, toId, itemKeyId, sum)
            }
            if (toId != fromId) changed(*LongArray(lotIds.size) { lotIds[it].raw })
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
            val moving = ArrayList<Pack>()
            eachRow(Keys.packAtHolderPrefix(fromId)) { cursor ->
                moving += packs.decode(fromId, KeyReader.u32(cursor.key(), 5), cursor.value())
            }
            val lots = ArrayList<Long>()
            for (pack in moving) {
                packs.move(this, pack, toId)
                addToTotal(this, fromId, pack.itemKeyId, -pack.sum)
                addToTotal(this, toId, pack.itemKeyId, pack.sum)
                for (lot in pack.lots) lots += lot
            }
            if (lots.isNotEmpty()) changed(*lots.toLongArray())
        }
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: error("no placement of $retiredLotId at $holder")
            val pack = packs.of(this, retiredLotId.raw)?.takeIf { it.holderId == holderId }
                ?: error("no placement of $retiredLotId at $holder")
            val i = pack.indexOf(retiredLotId.raw)
            check(i >= 0) { "no placement of $retiredLotId at $holder" }
            val previous = pack.remaining[i]

            // The replacement keeps the retired lot's queue slot: for everyone who comes after,
            // it is still the oldest thing in this account.
            val lots = pack.lots.copyOf().also { it[i] = newLotId.raw }
            val left = pack.remaining.copyOf().also { it[i] = remaining.raw }
            packs.swap(this, pack, Pack(pack.id, holderId, pack.itemKeyId, lots, left, pack.fifo))
            delete(Keys.lotPack(retiredLotId.raw))
            put(Keys.lotPack(newLotId.raw), Records.long(pack.id))
            addToTotal(this, holderId, pack.itemKeyId, remaining.raw - previous)
            changed(retiredLotId.raw, newLotId.raw)
        }
    }

    private fun rawsOf(ids: List<LotId>): LongArray =
        LongArray(ids.size) { ids[it].raw }.also { java.util.Arrays.sort(it) }

    private fun StorageUnit.readEdgesFrom(lotId: LotId): MutableList<LotEdge> {
        val out = ArrayList<LotEdge>()
        eachRow(Keys.edgeFromPrefix(lotId.raw)) { cursor ->
            out += decodeEdge(this, cursor.value(), parent = lotId.raw, child = KeyReader.u64(cursor.key(), 9))
        }
        return out
    }

    override suspend fun edgesInto(lotId: LotId): List<LotEdge> = storage.read {
        readEdgesInto(lotId)
    }

    private fun StorageUnit.readEdgesInto(lotId: LotId): MutableList<LotEdge> {
        val out = ArrayList<LotEdge>()
        eachRow(Keys.edgeIntoPrefix(lotId.raw)) { cursor ->
            out += decodeEdge(this, cursor.value(), parent = KeyReader.u64(cursor.key(), 9), child = lotId.raw)
        }
        return out
    }

    init {
        storage.afterReplace(::forget)
    }

    fun forget() {
        lots.invalidateAll()
    }


    private class Entries {
        private val lots = ArrayList<Long>()
        private val remaining = ArrayList<Long>()
        private val fifo = ArrayList<Long>()
        var sum = 0L
            private set

        fun add(lot: Long, left: Long, at: Long) {
            lots += lot
            remaining += left
            fifo += at
            sum += left
        }

        fun lots() = lots.toLongArray()
        fun remaining() = remaining.toLongArray()
        fun fifo() = fifo.toLongArray()
    }

    private class Split(val taken: LotPortion, val keptLotId: Long, val keptRemaining: Long)

    private fun split(
        unit: StorageUnit,
        itemKey: ItemKey,
        itemKeyId: Int,
        parentLotId: Long,
        remaining: Long,
        take: Long,
        txn: TxnId,
    ): Split {
        val takenId = counters.nextLotIdOn(unit)
        val keptId = counters.nextLotIdOn(unit)
        val takenQty = Quantity(take)
        val keptQty = Quantity(remaining - take)
        unit.put(Keys.lot(takenId.raw), Records.lot(itemKeyId, takenQty.raw, txn.raw))
        unit.put(Keys.lot(keptId.raw), Records.lot(itemKeyId, keptQty.raw, txn.raw))
        unit.cache(takenId, Lot(takenId, itemKey, takenQty, txn))
        unit.cache(keptId, Lot(keptId, itemKey, keptQty, txn))
        val splitTaken = Records.edge(Records.EDGE_SPLIT, takenQty.raw, 0, 0)
        val splitKept = Records.edge(Records.EDGE_SPLIT, keptQty.raw, 0, 0)
        unit.put(Keys.edgeFrom(parentLotId, takenId.raw), splitTaken)
        unit.put(Keys.edgeInto(takenId.raw, parentLotId), splitTaken)
        unit.put(Keys.edgeFrom(parentLotId, keptId.raw), splitKept)
        unit.put(Keys.edgeInto(keptId.raw, parentLotId), splitKept)
        return Split(LotPortion(takenId, takenQty), keptId.raw, keptQty.raw)
    }

    private fun addToTotal(unit: StorageUnit, holderId: Int, itemKeyId: Int, delta: Long) {
        if (delta == 0L) return
        val key = Keys.total(holderId, itemKeyId)
        val updated = (unit.get(key)?.let(Records::asLong) ?: 0L) + delta
        if (updated == 0L) unit.delete(key) else unit.put(key, Records.long(updated))
    }

    private fun accountLot(unit: StorageUnit, holder: HolderId, pack: Pack, i: Int): AccountLot = AccountLot(
        holder,
        readLot(unit, LotId(pack.lots[i])),
        Quantity(pack.remaining[i]),
        Seq(pack.fifo[i]),
    )

    private fun readLot(unit: StorageUnit, id: LotId): Lot {
        lots.getIfPresent(id)?.let { return it }
        val value = unit.get(Keys.lot(id.raw)) ?: error("lot $id does not exist")
        return decodeLot(unit, id, value).also { unit.cache(id, it) }
    }

    private fun decodeLot(unit: StorageUnit, id: LotId, value: MemorySegment): Lot = Lot(
        id,
        interning.resolveItemKey(unit, Records.lotItemKeyId(value)),
        Quantity(Records.lotQuantity(value)),
        TxnId(Records.lotCreatedBy(value)),
    )

    private fun decodeEdge(
        unit: StorageUnit,
        value: MemorySegment,
        parent: Long,
        child: Long,
    ): LotEdge {
        val quantity = Quantity(Records.edgeQuantity(value))
        return when (val kind = Records.edgeKind(value)) {
            Records.EDGE_SPLIT -> LotEdge.Split(LotId(child), LotId(parent), quantity)
            Records.EDGE_TRANSFORM -> LotEdge.Transform(
                LotId(child),
                LotId(parent),
                quantity,
                TxnId(Records.edgeReference(value)),
                interning.resolveHolder(unit, Records.edgeProducedAt(value)),
            )

            Records.EDGE_COMPENSATE -> LotEdge.Compensate(
                LotId(child),
                LotId(parent),
                quantity,
                RollbackJobId(Records.edgeReference(value)),
            )

            else -> error("unrecognized lot edge kind: $kind")
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
