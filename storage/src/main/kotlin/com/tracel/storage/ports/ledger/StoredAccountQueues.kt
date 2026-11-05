package com.tracel.storage.ports.ledger

import com.tracel.engine.ledger.LotPortion
import com.tracel.engine.ledger.repository.AccountQueues
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.EdgeRecord
import com.tracel.storage.codec.LotRecord
import com.tracel.storage.codec.Scalar
import com.tracel.storage.intern.Interning
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.util.eachRow
import it.unimi.dsi.fastutil.longs.LongArrayList

/**
 * Each holder's queue per item key, oldest lot first, and the consuming of it:
 * a take splits the last lot it needs into the part it takes and the part that
 * keeps its place in the queue.
 */
internal class StoredAccountQueues(
    private val storage: TracelStorage,
    private val counters: Counters,
    private val cache: LotCache,
    private val edges: StoredLotEdges,
    private val packs: Packs,
) : AccountQueues {
    private val interning: Interning get() = storage.interning

    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        scan(Tag.PACK_AT[holderId, itemKeyId]).use { cursor ->
            while (out.size < limit && cursor.next()) {
                val pack = packs.decode(holderId, itemKeyId, cursor.value())
                var i = 0
                while (i < pack.size && out.size < limit) out += cache.accountLot(this, holder, pack, i++)
            }
        }
        out
    }

    override suspend fun takeFifo(
        holder: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): List<LotPortion> = storage.write {
        val holderId = interning.findHolderId(this, holder)
            ?: error("insufficient balance at $holder for $itemKey: needed ${quantity.raw}, have 0.")
        val itemKeyId = interning.findItemKeyId(this, itemKey)
            ?: error("insufficient balance at $holder for $itemKey: needed ${quantity.raw}, have 0.")
        consume(this, holder, holderId, itemKey, itemKeyId, longArrayOf(quantity.raw), strict = true, txn)[0]
    }

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
        unit.scan(Tag.PACK_AT[holderId, itemKeyId]).use { cursor ->
            while (have < want && cursor.next()) {
                val pack = packs.decode(holderId, itemKeyId, cursor.value())
                queue += pack
                have += pack.sum
            }
        }
        check(!strict || have >= want) {
            "insufficient balance at $holder for $itemKey: needed $want, short by ${want - have}."
        }

        val out = Array(wants.size) { ArrayList<LotPortion>(4) }
        var d = 0
        var still = wants[0]
        var spent = 0L
        val touched = LongArrayList()
        var done = false
        for (pack in queue) {
            if (done) break
            val keep = BooleanArray(pack.size) { true }
            var lots: LongArray? = null
            var remaining: LongArray? = null
            for (i in 0 until pack.size) {
                var lot = pack.lots[i]
                var left = pack.remaining[i]
                touched.add(lot)
                while (left > 0L) {
                    while (still == 0L && d + 1 < wants.size) still = wants[++d]
                    if (still == 0L) {
                        done = true
                        break
                    }
                    if (left <= still) {
                        keep[i] = false
                        unit.delete(Tag.LOT_PACK[lot])
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
                        unit.delete(Tag.LOT_PACK[lot])
                        unit.put(Tag.LOT_PACK[split.keptLotId], Scalar.long(pack.id))
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
        if (touched.isNotEmpty()) cache.changed(unit, *touched.toLongArray())
        return out
    }

    override suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read 0L
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read 0L
        get(Tag.TOTAL[holderId, itemKeyId])?.let(Scalar::asLong) ?: 0L
    }

    override suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyMap()
        val out = LinkedHashMap<ItemKey, Long>()
        eachRow(Tag.TOTAL[holderId]) { cursor ->
            val total = Scalar.asLong(cursor.value())
            if (total > 0) out[interning.resolveItemKey(this, KeyReader.u32(cursor.key(), 5))] = total
        }
        out
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
        unit.put(Tag.LOT[takenId.raw], LotRecord.of(itemKeyId, takenQty.raw, txn.raw))
        unit.put(Tag.LOT[keptId.raw], LotRecord.of(itemKeyId, keptQty.raw, txn.raw))
        cache.put(unit, takenId, Lot(takenId, itemKey, takenQty, txn))
        cache.put(unit, keptId, Lot(keptId, itemKey, keptQty, txn))
        val splitTaken = EdgeRecord.of(EdgeRecord.SPLIT, takenQty.raw, 0, 0)
        val splitKept = EdgeRecord.of(EdgeRecord.SPLIT, keptQty.raw, 0, 0)
        edges.link(unit, parentLotId, takenId.raw, splitTaken)
        edges.link(unit, parentLotId, keptId.raw, splitKept)
        return Split(LotPortion(takenId, takenQty), keptId.raw, keptQty.raw)
    }
}

/** Moves [holderId]'s running total of [itemKeyId] by [delta]; a total of zero is no row at all. */
internal fun addToTotal(unit: StorageUnit, holderId: Int, itemKeyId: Int, delta: Long) {
    if (delta == 0L) return
    val key = Tag.TOTAL[holderId, itemKeyId]
    val updated = (unit.get(key)?.let(Scalar::asLong) ?: 0L) + delta
    if (updated == 0L) unit.delete(key) else unit.put(key, Scalar.long(updated))
}
