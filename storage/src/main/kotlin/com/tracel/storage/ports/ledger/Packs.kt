package com.tracel.storage.ports.ledger

import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ports.ops.Counters
import java.lang.foreign.MemorySegment
import com.tracel.storage.codec.records.Lot as LotRecord

/** How many lots one pack holds at most: the ceiling on what any single-lot edit has to rewrite. */
internal const val PACK_CAP = 256

/**
 * Lots that sit together in one account, oldest first.
 *
 * Immutable: every edit is a new [Pack], usually under the same [id].
 */
internal class Pack(
    val id: Long,
    val holderId: Int,
    val itemKeyId: Int,
    val lots: LongArray,
    val remaining: LongArray,
    val fifo: LongArray,
) {
    val size: Int get() = lots.size
    val head: Long get() = fifo[0]
    val tail: Long get() = fifo[size - 1]
    val sum: Long get() = remaining.sum()

    /** The index of [lot] in [lots], or `-1` if it is not there. */
    fun indexOf(lot: Long): Int {
        for (i in lots.indices) if (lots[i] == lot) return i
        return -1
    }

    /** The entries [keep] says stay, or `null` when none do. */
    fun keeping(keep: BooleanArray): Pack? {
        val n = keep.count { it }
        if (n == 0) return null
        if (n == size) return this
        val l = LongArray(n)
        val r = LongArray(n)
        val f = LongArray(n)
        var j = 0
        for (i in 0 until size) if (keep[i]) {
            l[j] = lots[i]; r[j] = remaining[i]; f[j] = fifo[i]; j++
        }
        return Pack(id, holderId, itemKeyId, l, r, f)
    }

    /** @return a pack with the same entries as this. */
    fun at(holderId: Int): Pack = Pack(id, holderId, itemKeyId, lots, remaining, fifo)
}

/**
 * The pack keyspace, one level below the lot repository: `packAt` holds each pack in its queue slot,
 * keyed by its newest entry; `pack` says where a pack is; `lotPack` says which pack a lot is in.
 */
internal class Packs(private val counters: Counters) {
    /** Decodes a memory segment into a [Pack] object, extracting lot, remaining, and FIFO values. */
    fun decode(holderId: Int, itemKeyId: Int, value: MemorySegment): Pack {
        val n = LotRecord.packCount(value)
        val lots = LongArray(n)
        val remaining = LongArray(n)
        val fifo = LongArray(n)
        for (i in 0 until n) {
            lots[i] = LotRecord.packLot(value, i)
            remaining[i] = LotRecord.packRemaining(value, i)
            fifo[i] = LotRecord.packFifo(value, i)
        }
        return Pack(LotRecord.packId(value), holderId, itemKeyId, lots, remaining, fifo)
    }

    /** Reads a pack from the given storage unit using its ID. */
    fun read(unit: StorageUnit, packId: Long): Pack? {
        val location = unit.get(Keys.pack(packId)) ?: return null
        val holderId = LotRecord.locationHolderId(location)
        val itemKeyId = LotRecord.locationItemKeyId(location)
        val slot = unit.get(Keys.packAt(holderId, itemKeyId, LotRecord.locationTail(location)))
            ?: error("pack $packId points at a queue slot that is empty")
        return decode(holderId, itemKeyId, slot)
    }

    /** Retrieves a pack from the specified storage unit that corresponds to the given lot. */
    fun of(unit: StorageUnit, lot: Long): Pack? =
        unit.get(Keys.lotPack(lot))?.let { read(unit, Records.asLong(it)) }

    /** Retrieves the holder ID of the pack with the given ID. */
    fun holderIdOf(unit: StorageUnit, packId: Long): Int? =
        unit.get(Keys.pack(packId))?.let(LotRecord::locationHolderId)

    /** [old] becomes [new], or goes. Lot pointers are the caller's. */
    fun swap(unit: StorageUnit, old: Pack, new: Pack?) {
        val slotMoved = new == null || new.holderId != old.holderId || new.itemKeyId != old.itemKeyId || new.tail != old.tail
        if (slotMoved) {
            unit.delete(Keys.packAt(old.holderId, old.itemKeyId, old.tail))
            unit.delete(Keys.packItem(old.itemKeyId, old.holderId, old.tail))
        }
        if (new == null || new.id != old.id) unit.delete(Keys.pack(old.id))
        if (new != null) write(unit, new)
    }

    /** Puts entries, oldest first, into an account's queue. */
    fun insert(unit: StorageUnit, holderId: Int, itemKeyId: Int, lots: LongArray, remaining: LongArray, fifo: LongArray) {
        if (lots.isEmpty()) return
        val overlapping = overlapping(unit, holderId, itemKeyId, fifo[0], fifo[fifo.size - 1])
        if (overlapping.isEmpty()) return chunks(unit, holderId, itemKeyId, lots, remaining, fifo)

        var n = lots.size
        for (pack in overlapping) n += pack.size
        val order = arrayOfNulls<LongArray>(n)
        var k = 0
        for (i in lots.indices) order[k++] = longArrayOf(fifo[i], lots[i], remaining[i])
        for (pack in overlapping) {
            for (i in 0 until pack.size) order[k++] = longArrayOf(pack.fifo[i], pack.lots[i], pack.remaining[i])
            swap(unit, pack, null)
        }
        order.sortBy { it!![0] }
        chunks(
            unit, holderId, itemKeyId,
            LongArray(n) { order[it]!![1] },
            LongArray(n) { order[it]!![2] },
            LongArray(n) { order[it]!![0] },
        )
    }

    /** Moves a whole pack to [holderId]: its rows only, unless it would interleave with what is there. */
    fun move(unit: StorageUnit, pack: Pack, holderId: Int) {
        if (pack.holderId == holderId) return
        if (overlapping(unit, holderId, pack.itemKeyId, pack.head, pack.tail).isEmpty()) {
            swap(unit, pack, pack.at(holderId))
        } else {
            swap(unit, pack, null)
            insert(unit, holderId, pack.itemKeyId, pack.lots, pack.remaining, pack.fifo)
        }
    }

    private fun overlapping(unit: StorageUnit, holderId: Int, itemKeyId: Int, head: Long, tail: Long): List<Pack> {
        var out: ArrayList<Pack>? = null
        unit.scan(Keys.packAtPrefix(holderId, itemKeyId), Keys.packAt(holderId, itemKeyId, head)).use { cursor ->
            while (cursor.next()) {
                val value = cursor.value()
                if (LotRecord.packFifo(value, 0) > tail) break
                (out ?: ArrayList<Pack>().also { out = it }) += decode(holderId, itemKeyId, value)
            }
        }
        return out ?: emptyList()
    }

    private fun chunks(unit: StorageUnit, holderId: Int, itemKeyId: Int, lots: LongArray, remaining: LongArray, fifo: LongArray) {
        var from = 0
        while (from < lots.size) {
            val until = minOf(from + PACK_CAP, lots.size)
            val pack = Pack(
                counters.nextPackIdOn(unit),
                holderId,
                itemKeyId,
                lots.copyOfRange(from, until),
                remaining.copyOfRange(from, until),
                fifo.copyOfRange(from, until),
            )
            write(unit, pack)
            val pointer = Records.long(pack.id)
            for (lot in pack.lots) unit.put(Keys.lotPack(lot), pointer)
            from = until
        }
    }

    private fun write(unit: StorageUnit, pack: Pack) {
        unit.put(
            Keys.packAt(pack.holderId, pack.itemKeyId, pack.tail),
            LotRecord.pack(pack.id, pack.lots, pack.remaining, pack.fifo, pack.size),
        )
        unit.put(Keys.pack(pack.id), LotRecord.packLocation(pack.holderId, pack.itemKeyId, pack.tail))
        unit.put(Keys.packItem(pack.itemKeyId, pack.holderId, pack.tail), LotRecord.packSum(pack.id, pack.sum))
    }
}
