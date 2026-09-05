package com.tracel.storage.ports.ledger

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.annotations.Consume
import com.tracel.engine.ledger.LotPortion
import com.tracel.engine.ledger.LotRepository as LotRepositoryPort
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning
import com.tracel.storage.ports.log.QueryProbe
import com.tracel.storage.ports.log.walkWanted
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment

/** [LotRepositoryPort] over the packed keyspace. */
class LotRepository(
    private val storage: TracelStorage,
    private val counters: Counters,
) : LotRepositoryPort, UnitOfWork by storage {
    private val interning: Interning get() = storage.interning
    private val lots: Cache<LotId, Lot> = Caffeine.newBuilder().maximumSize(100_000).build()
    private val fifoScratch = FifoScratch()

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        val id = counters.nextLotId()
        return storage.write {
            val itemKeyId = interning.internItemKey(this, itemKey)
            put(Keys.lot(id.raw), Records.lot(itemKeyId, quantity.raw, createdBy.raw))
            Lot(id, itemKey, quantity, createdBy).also { lots.put(id, it) }
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
        }
    }

    override suspend fun removeEdge(parent: LotId, child: LotId) {
        storage.write {
            delete(Keys.edgeFrom(parent.raw, child.raw))
            delete(Keys.edgeInto(child.raw, parent.raw))
        }
    }

    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = storage.read {
        readEdgesFrom(lotId)
    }

    override suspend fun findCompensateEdge(originalLotId: LotId, job: RollbackJobId): LotEdge.Compensate? = storage.read {
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
                lots.put(id, decodeLot(this, id, cursor.value()))
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
                out[id] = decodeLot(this, id, cursor.value()).also { lots.put(id, it) }
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
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Keys.PLACE_REV, sorted, 0, sorted.size, Keys::placeRevPrefix) { at, cursor ->
                val id = LotId(at)
                if (id !in out) out[id] = interning.resolveHolder(this, cursor.keyU32(9))
            }
            out
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

    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        scan(Keys.placePrefix(holderId, itemKeyId)).use { cursor ->
            while (out.size < limit && cursor.next()) {
                out += accountLot(this, holder, cursor.key(), cursor.value())
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
        val buf = fifoScratch
        buf.clear()
        var need = quantity.raw
        scan(Keys.placePrefix(holderId, itemKeyId)).use { cursor ->
            while (need > 0 && cursor.next()) {
                val value = cursor.value()
                val remaining = Records.placementRemaining(value)
                buf.add(cursor.keyU64(9), Records.placementLotId(value), remaining)
                need -= if (remaining <= need) remaining else need
            }
        }
        check(need == 0L) {
            "insufficient balance at $holder for $itemKey: needed ${quantity.raw}, short by $need"
        }
        val taken = ArrayList<LotPortion>(buf.n)
        var still = quantity.raw
        var i = 0
        while (i < buf.n && still > 0) {
            val remaining = buf.rem[i]
            val lotId = buf.lots[i]
            val fifo = buf.fifo[i]
            if (remaining <= still) {
                consumeSlot(this, holderId, itemKeyId, fifo, lotId, remaining)
                taken += LotPortion(LotId(lotId), Quantity(remaining))
                still -= remaining
            } else {
                taken += splitSlot(this, holderId, itemKeyId, itemKey, fifo, lotId, remaining, still, txn).taken
                still = 0
            }
            i++
        }
        taken
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
        val buf = fifoScratch
        buf.clear()
        var want = 0L
        var o = 0
        while (o < owed.size) {
            want += owed[o].second
            o++
        }
        var have = 0L
        scan(Keys.placePrefix(holderId, itemKeyId)).use { cursor ->
            while ((want == 0L || have < want) && cursor.next()) {
                val value = cursor.value()
                val remaining = Records.placementRemaining(value)
                buf.add(cursor.keyU64(9), Records.placementLotId(value), remaining)
                have += remaining
            }
        }
        val out = ArrayList<Pair<HolderId, List<LotPortion>>>(owed.size)
        var slot = 0
        var lotId = if (buf.n == 0) 0L else buf.lots[0]
        var left = if (buf.n == 0) 0L else buf.rem[0]
        o = 0
        while (o < owed.size) {
            val dest = owed[o]
            var stillNeeded = dest.second
            var taken: ArrayList<LotPortion>? = null
            while (stillNeeded > 0 && slot < buf.n) {
                val fifo = buf.fifo[slot]
                val take = if (left <= stillNeeded) left else stillNeeded
                if (take == left) {
                    consumeSlot(this, holderId, itemKeyId, fifo, lotId, left)
                    val bucket = taken ?: ArrayList<LotPortion>(4).also { taken = it }
                    bucket += LotPortion(LotId(lotId), Quantity(take))
                    stillNeeded -= take
                    slot++
                    if (slot < buf.n) {
                        lotId = buf.lots[slot]
                        left = buf.rem[slot]
                    } else {
                        left = 0L
                    }
                } else {
                    val split = splitSlot(this, holderId, itemKeyId, itemKey, fifo, lotId, left, take, txn)
                    val bucket = taken ?: ArrayList<LotPortion>(4).also { taken = it }
                    bucket += split.taken
                    lotId = split.keptLotId
                    left = split.keptRemaining
                    stillNeeded = 0
                }
            }
            val got = taken
            if (!got.isNullOrEmpty()) out += dest.first to got
            o++
        }
        out
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
        val reverse = get(Keys.placeRev(lotId.raw, holderId)) ?: return@read null
        val itemKeyId = Records.placementRevItemKeyId(reverse)
        val fifoSeq = Records.placementRevFifoSeq(reverse)
        val key = Keys.place(holderId, itemKeyId, fifoSeq)
        get(key)?.let { accountLot(this, holder, key, it) }
    }

    override suspend fun census(itemKey: ItemKey): Long = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read 0L
        var total = 0L
        eachRow(Keys.placeItemPrefix(itemKeyId)) { cursor ->
            val holderId = KeyReader.u32(cursor.key(), 5)
            when (interning.resolveHolder(this, holderId)) {
                is HolderId.Source, is HolderId.Sink -> return@eachRow
                else -> {
                    val fifoSeq = KeyReader.u64(cursor.key(), 9)
                    val placement = get(Keys.place(holderId, itemKeyId, fifoSeq)) ?: return@eachRow
                    total += Records.placementRemaining(placement)
                }
            }
        }
        total
    }

    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        eachRow(Keys.placeItemPrefix(itemKeyId)) { cursor ->
            val holderId = KeyReader.u32(cursor.key(), 5)
            val fifoSeq = KeyReader.u64(cursor.key(), 9)
            val placement = get(Keys.place(holderId, itemKeyId, fifoSeq)) ?: return@eachRow
            out += AccountLot(
                interning.resolveHolder(this, holderId),
                readLot(this, LotId(Records.placementLotId(placement))),
                Quantity(Records.placementRemaining(placement)),
                Seq(fifoSeq),
            )
        }
        out
    }

    override suspend fun placementsAt(holder: HolderId): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        eachRow(Keys.placeHolderPrefix(holderId)) { cursor -> out += accountLot(this, holder, cursor.key(), cursor.value()) }
        out
    }

    override suspend fun currentHolderOf(lotId: LotId): HolderId? = storage.read {
        scan(Keys.placeRevPrefix(lotId.raw)).use { cursor ->
            if (!cursor.next()) null else interning.resolveHolder(this, KeyReader.u32(cursor.key(), 9))
        }
    }

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot {
        val fifoSeq = counters.nextFifoSeq()
        return storage.write {
            val lot = readLot(this, lotId)
            val holderId = interning.internHolder(this, holder)
            val itemKeyId = interning.internItemKey(this, lot.itemKey)
            put(Keys.place(holderId, itemKeyId, fifoSeq.raw), Records.placement(lotId.raw, quantity.raw))
            put(Keys.placeRev(lotId.raw, holderId), Records.placementRev(itemKeyId, fifoSeq.raw))
            put(Keys.placeItem(itemKeyId, holderId, fifoSeq.raw), EMPTY)
            addToTotal(this, holderId, itemKeyId, quantity.raw)
            AccountLot(holder, lot, quantity, fifoSeq)
        }
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: return@write
            val reverseKey = Keys.placeRev(lotId.raw, holderId)
            val reverse = get(reverseKey) ?: return@write
            val itemKeyId = Records.placementRevItemKeyId(reverse)
            val fifoSeq = Records.placementRevFifoSeq(reverse)
            val placementKey = Keys.place(holderId, itemKeyId, fifoSeq)
            val remaining = get(placementKey)?.let(Records::placementRemaining) ?: 0L

            delete(placementKey)
            delete(reverseKey)
            delete(Keys.placeItem(itemKeyId, holderId, fifoSeq))
            addToTotal(this, holderId, itemKeyId, -remaining)
        }
    }

    override suspend fun rehome(from: HolderId, to: HolderId, lotId: LotId) {
        storage.write {
            if (from == to) return@write
            val fromId = interning.findHolderId(this, from)
                ?: error("lot $lotId is not currently placed at $from")
            val toId = interning.internHolder(this, to)
            val reverseKey = Keys.placeRev(lotId.raw, fromId)
            val reverse = get(reverseKey) ?: error("lot $lotId is not currently placed at $from")
            val itemKeyId = Records.placementRevItemKeyId(reverse)
            val fifoSeq = Records.placementRevFifoSeq(reverse)
            val placementKey = Keys.place(fromId, itemKeyId, fifoSeq)
            val remaining = get(placementKey)?.let(Records::placementRemaining) ?: return@write

            delete(placementKey)
            delete(reverseKey)
            delete(Keys.placeItem(itemKeyId, fromId, fifoSeq))
            put(Keys.place(toId, itemKeyId, fifoSeq), Records.placement(lotId.raw, remaining))
            put(Keys.placeRev(lotId.raw, toId), Records.placementRev(itemKeyId, fifoSeq))
            put(Keys.placeItem(itemKeyId, toId, fifoSeq), EMPTY)
            addToTotal(this, fromId, itemKeyId, -remaining)
            addToTotal(this, toId, itemKeyId, remaining)
        }
    }

    override suspend fun relocate(from: HolderId, to: HolderId) {
        storage.write {
            val fromId = interning.findHolderId(this, from) ?: return@write
            val toId = interning.internHolder(this, to)
            if (fromId == toId) return@write

            // Read the whole account out before touching any of it: rewriting keys under a cursor
            // that is still walking the same prefix is a good way to visit a key twice or not at all.
            val moving = ArrayList<Moved>()
            eachRow(Keys.placeHolderPrefix(fromId)) { cursor ->
                val key = cursor.key()
                val value = cursor.value()
                moving += Moved(
                    KeyReader.u32(key, 5),
                    KeyReader.u64(key, 9),
                    Records.placementLotId(value),
                    Records.placementRemaining(value),
                )
            }

            for ((itemKeyId, fifoSeq, lotId, remaining) in moving) {
                delete(Keys.place(fromId, itemKeyId, fifoSeq))
                delete(Keys.placeRev(lotId, fromId))
                delete(Keys.placeItem(itemKeyId, fromId, fifoSeq))

                // The queue position travels with the lot, so a relocated account keeps its FIFO
                // order both internally and against whatever already sat at the destination.
                put(Keys.place(toId, itemKeyId, fifoSeq), Records.placement(lotId, remaining))
                put(Keys.placeRev(lotId, toId), Records.placementRev(itemKeyId, fifoSeq))
                put(Keys.placeItem(itemKeyId, toId, fifoSeq), EMPTY)

                addToTotal(this, fromId, itemKeyId, -remaining)
                addToTotal(this, toId, itemKeyId, remaining)
            }
        }
    }

    private data class Moved(val itemKeyId: Int, val fifoSeq: Long, val lotId: Long, val remaining: Long)

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: error("no placement of $retiredLotId at $holder")
            val reverseKey = Keys.placeRev(retiredLotId.raw, holderId)
            val reverse = get(reverseKey) ?: error("no placement of $retiredLotId at $holder")
            val itemKeyId = Records.placementRevItemKeyId(reverse)
            val fifoSeq = Records.placementRevFifoSeq(reverse)
            val placementKey = Keys.place(holderId, itemKeyId, fifoSeq)
            val previous = get(placementKey)?.let(Records::placementRemaining) ?: 0L

            // The replacement keeps the retired lot's queue slot: for everyone who comes after,
            // it is still the oldest thing in this account.
            put(placementKey, Records.placement(newLotId.raw, remaining.raw))
            delete(reverseKey)
            put(Keys.placeRev(newLotId.raw, holderId), Records.placementRev(itemKeyId, fifoSeq))
            addToTotal(this, holderId, itemKeyId, remaining.raw - previous)
        }
    }

    fun forget() {
        lots.invalidateAll()
    }

    private class FifoScratch {
        var fifo = LongArray(32)
        var lots = LongArray(32)
        var rem = LongArray(32)
        var n = 0

        fun clear() {
            n = 0
        }

        fun add(fifoSeq: Long, lotId: Long, remaining: Long) {
            if (n == fifo.size) {
                val cap = n * 2
                fifo = fifo.copyOf(cap)
                lots = lots.copyOf(cap)
                rem = rem.copyOf(cap)
            }
            fifo[n] = fifoSeq
            lots[n] = lotId
            rem[n] = remaining
            n++
        }
    }

    private class Split(val taken: LotPortion, val keptLotId: Long, val keptRemaining: Long)

    private fun consumeSlot(
        unit: StorageUnit,
        holderId: Int,
        itemKeyId: Int,
        fifoSeq: Long,
        lotId: Long,
        remaining: Long,
    ) {
        unit.delete(Keys.place(holderId, itemKeyId, fifoSeq))
        unit.delete(Keys.placeRev(lotId, holderId))
        unit.delete(Keys.placeItem(itemKeyId, holderId, fifoSeq))
        addToTotal(unit, holderId, itemKeyId, -remaining)
    }

    private fun splitSlot(
        unit: StorageUnit,
        holderId: Int,
        itemKeyId: Int,
        itemKey: ItemKey,
        fifoSeq: Long,
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
        lots.put(takenId, Lot(takenId, itemKey, takenQty, txn))
        lots.put(keptId, Lot(keptId, itemKey, keptQty, txn))
        val splitTaken = Records.edge(Records.EDGE_SPLIT, takenQty.raw, 0, 0)
        val splitKept = Records.edge(Records.EDGE_SPLIT, keptQty.raw, 0, 0)
        unit.put(Keys.edgeFrom(parentLotId, takenId.raw), splitTaken)
        unit.put(Keys.edgeInto(takenId.raw, parentLotId), splitTaken)
        unit.put(Keys.edgeFrom(parentLotId, keptId.raw), splitKept)
        unit.put(Keys.edgeInto(keptId.raw, parentLotId), splitKept)
        val placementKey = Keys.place(holderId, itemKeyId, fifoSeq)
        unit.put(placementKey, Records.placement(keptId.raw, keptQty.raw))
        unit.delete(Keys.placeRev(parentLotId, holderId))
        unit.put(Keys.placeRev(keptId.raw, holderId), Records.placementRev(itemKeyId, fifoSeq))
        addToTotal(unit, holderId, itemKeyId, -take)
        return Split(LotPortion(takenId, takenQty), keptId.raw, keptQty.raw)
    }

    private fun addToTotal(unit: StorageUnit, holderId: Int, itemKeyId: Int, delta: Long) {
        if (delta == 0L) return
        val key = Keys.total(holderId, itemKeyId)
        val updated = (unit.get(key)?.let(Records::asLong) ?: 0L) + delta
        if (updated == 0L) unit.delete(key) else unit.put(key, Records.long(updated))
    }

    private fun readLot(unit: StorageUnit, id: LotId): Lot {
        lots.getIfPresent(id)?.let { return it }
        val value = unit.get(Keys.lot(id.raw)) ?: error("lot $id does not exist")
        return decodeLot(unit, id, value).also { lots.put(id, it) }
    }

    private fun decodeLot(unit: StorageUnit, id: LotId, value: MemorySegment): Lot = Lot(
        id,
        interning.resolveItemKey(unit, Records.lotItemKeyId(value)),
        Quantity(Records.lotQuantity(value)),
        TxnId(Records.lotCreatedBy(value)),
    )

    private fun accountLot(
        unit: StorageUnit,
        holder: HolderId,
        placementKey: ByteArray,
        placement: MemorySegment,
    ): AccountLot = AccountLot(
        holder,
        readLot(unit, LotId(Records.placementLotId(placement))),
        Quantity(Records.placementRemaining(placement)),
        Seq(KeyReader.u64(placementKey, 9)),
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
