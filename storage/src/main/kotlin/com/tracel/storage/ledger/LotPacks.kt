package com.tracel.storage.ledger

import com.tracel.storage.StorageUnit
import com.tracel.storage.admin.StoredCounters
import com.tracel.storage.codec.*
import java.lang.foreign.MemorySegment

/** How many lots one pack holds at most. */
internal const val PACK_CAP = 256

/**
 * Lots that sit together in one account, oldest first.
 *
 * Immutable: every edit is a new [LotPack], usually under the same [id].
 */
internal class LotPack(
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
    fun keeping(keep: BooleanArray): LotPack? {
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
        return LotPack(id, holderId, itemKeyId, l, r, f)
    }

    /** @return a pack with the same entries as this. */
    fun at(holderId: Int): LotPack = LotPack(id, holderId, itemKeyId, lots, remaining, fifo)
}

/**
 * The pack keyspace, one level below the lot repository: `packAt` holds each pack in its queue slot,
 * keyed by its newest entry; `pack` says where a pack is; `lotPack` says which pack a lot is in.
 */
internal class LotPacks(private val counters: StoredCounters) {
    /** Decodes a memory segment into a [LotPack] object, extracting lot, remaining, and FIFO values. */
    fun decode(holderId: Int, itemKeyId: Int, value: MemorySegment): LotPack {
        val n = PackRecord.count[value]
        val lots = LongArray(n)
        val remaining = LongArray(n)
        val fifo = LongArray(n)
        for (i in 0 until n) {
            lots[i] = PackRecord.lot[value, i]
            remaining[i] = PackRecord.remaining[value, i]
            fifo[i] = PackRecord.fifo[value, i]
        }
        return LotPack(PackRecord.id[value], holderId, itemKeyId, lots, remaining, fifo)
    }

    /** Reads a pack from the given storage unit using its ID. */
    fun read(unit: StorageUnit, packId: Long): LotPack? {
        val location = unit.get(Tag.PACK[packId]) ?: return null
        val holderId = PackLocation.holderId[location]
        val itemKeyId = PackLocation.itemKeyId[location]
        val slot = unit.get(Tag.PACK_AT[holderId, itemKeyId, PackLocation.tail[location]])
            ?: error("pack $packId points at a queue slot that is empty.")
        return decode(holderId, itemKeyId, slot)
    }

    /** Retrieves a pack from the specified storage unit that corresponds to the given lot. */
    fun of(unit: StorageUnit, lot: Long): LotPack? =
        unit.get(Tag.LOT_PACK[lot])?.let { read(unit, Scalar.asLong(it)) }

    /** Retrieves the holder ID of the pack with the given ID. */
    fun holderIdOf(unit: StorageUnit, packId: Long): Int? =
        unit.get(Tag.PACK[packId])?.let { PackLocation.holderId[it] }

    /** [old] becomes [new], or goes. Lot pointers are the caller's. */
    fun swap(unit: StorageUnit, old: LotPack, new: LotPack?) {
        val slotMoved =
            new == null || new.holderId != old.holderId || new.itemKeyId != old.itemKeyId || new.tail != old.tail
        if (slotMoved) {
            unit.delete(Tag.PACK_AT[old.holderId, old.itemKeyId, old.tail])
            unit.delete(Tag.PACK_ITEM[old.itemKeyId, old.holderId, old.tail])
        }
        if (new == null || new.id != old.id) unit.delete(Tag.PACK[old.id])
        if (new != null) write(unit, new)
    }

    /** Puts entries, oldest first, into an account's queue. */
    fun insert(
        unit: StorageUnit,
        holderId: Int,
        itemKeyId: Int,
        lots: LongArray,
        remaining: LongArray,
        fifo: LongArray
    ) {
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
    fun move(unit: StorageUnit, pack: LotPack, holderId: Int) {
        if (pack.holderId == holderId) return
        if (overlapping(unit, holderId, pack.itemKeyId, pack.head, pack.tail).isEmpty()) {
            swap(unit, pack, pack.at(holderId))
        } else {
            swap(unit, pack, null)
            insert(unit, holderId, pack.itemKeyId, pack.lots, pack.remaining, pack.fifo)
        }
    }

    private fun overlapping(unit: StorageUnit, holderId: Int, itemKeyId: Int, head: Long, tail: Long): List<LotPack> {
        var out: ArrayList<LotPack>? = null
        unit.scan(Tag.PACK_AT[holderId, itemKeyId], Tag.PACK_AT[holderId, itemKeyId, head]).use { cursor ->
            while (cursor.next()) {
                val value = cursor.value()
                if (PackRecord.fifo[value, 0] > tail) break
                (out ?: ArrayList<LotPack>().also { out = it }) += decode(holderId, itemKeyId, value)
            }
        }
        return out ?: emptyList()
    }

    private fun chunks(
        unit: StorageUnit,
        holderId: Int,
        itemKeyId: Int,
        lots: LongArray,
        remaining: LongArray,
        fifo: LongArray
    ) {
        var from = 0
        while (from < lots.size) {
            val until = minOf(from + PACK_CAP, lots.size)
            val pack = LotPack(
                counters.nextPackIdOn(unit),
                holderId,
                itemKeyId,
                lots.copyOfRange(from, until),
                remaining.copyOfRange(from, until),
                fifo.copyOfRange(from, until),
            )
            write(unit, pack)
            val pointer = Scalar.long(pack.id)
            for (lot in pack.lots) unit.put(Tag.LOT_PACK[lot], pointer)
            from = until
        }
    }

    private fun write(unit: StorageUnit, pack: LotPack) {
        unit.put(
            Tag.PACK_AT[pack.holderId, pack.itemKeyId, pack.tail],
            PackRecord.of(pack.id, pack.lots, pack.remaining, pack.fifo, pack.size),
        )
        unit.put(Tag.PACK[pack.id], PackLocation.of(pack.holderId, pack.itemKeyId, pack.tail))
        unit.put(Tag.PACK_ITEM[pack.itemKeyId, pack.holderId, pack.tail], PackSum.of(pack.id, pack.sum))
    }
}
