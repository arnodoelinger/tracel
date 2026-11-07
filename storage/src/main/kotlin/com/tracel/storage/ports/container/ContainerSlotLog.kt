package com.tracel.storage.ports.container

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.Chrono
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.SegmentReader
import com.tracel.storage.chrono.query.Sieve
import com.tracel.storage.ports.ops.Counters
import java.lang.foreign.MemorySegment
import com.tracel.engine.container.ContainerSlotLog as ContainerSlotLogPort

/** Where a container's contents sat, slot by slot, over time. */
class ContainerSlotLog(
    private val storage: TracelStorage,
    private val counters: Counters,
    private val chrono: Chrono = Chrono.of(storage),
) : ContainerSlotLogPort {
    override suspend fun record(holder: HolderId, epochMillis: Long, slots: List<ContainerSlotEntry>) {
        val seq = counters.nextSeq().raw
        storage.write {
            val interning = storage.interning
            val holderId = interning.internHolder(this, holder)
            val sink = ByteWriter(8 + slots.size * 8)
            sink.varint(slots.size.toLong())
            for ((slot, itemKey, quantity) in slots) {
                sink.varint(slot.toLong())
                sink.varint(interning.internItemKey(this, itemKey).toLong())
                sink.varint(quantity)
            }
            val tile = tileOf(this, holder)
            chrono.writer(this, Lane.CONTAINERS).addEntity(tile, seq, epochMillis, 0, holderId, 0, 0, 0L, 0L, sink.toByteArray(), null)
        }
    }

    override suspend fun layoutAt(holder: HolderId, asOfMillis: Long, limit: Int): List<ContainerSlotEntry>? {
        if (limit <= 0) return null
        return storage.read {
            val holderId = storage.interning.findHolderId(this, holder) ?: return@read null
            val reader = chrono.reader(this)
            val found = reader.select(listOf(tileOf(this, holder)), Sieve.ALL, Lane.CONTAINERS)
            val newest = ArrayList<Pair<Rows, Int>>()
            for (f in found) for (r in 0 until f.rows.size) if (f.rows.actor[r] == holderId) newest += f.rows to r
            newest.sortByDescending { (rows, r) -> rows.seq[r] }
            for ((rows, r) in newest.take(limit)) if (rows.millis[r] <= asOfMillis) return@read decode(this, rows.beforeExtrasAt(r))
            null
        }
    }

    private fun decode(unit: StorageUnit, blob: ByteArray?): List<ContainerSlotEntry> {
        if (blob == null) return emptyList()
        val c = SegmentReader(MemorySegment.ofArray(blob))
        val count = c.varintInt()
        return List(count) {
            ContainerSlotEntry(c.varintInt(), storage.interning.resolveItemKey(unit, c.varintInt()), c.varint())
        }
    }

    private fun tileOf(unit: StorageUnit, holder: HolderId): Tile {
        val pos = when (holder) {
            is HolderId.Block -> BlockPos(holder.world, holder.x, holder.y, holder.z)
            is HolderId.PlacedBlock -> BlockPos(holder.world, holder.x, holder.y, holder.z)
            else -> return Tile.of(0, 0, 0)
        }
        return Tile.at(storage.interning.internWorld(unit, pos.world), pos.x, pos.z)
    }
}
