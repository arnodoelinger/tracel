package com.tracel.storage.format.migrations

import com.tracel.engine.actor.VisitTimeline
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Chrono
import com.tracel.storage.chrono.ChronoKeys
import com.tracel.storage.chrono.ChronoKinds
import com.tracel.storage.chrono.ItemRows
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.RowsBuilder
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.TrackIds
import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.codec.BlockChange
import com.tracel.storage.codec.ContainerSlot
import com.tracel.storage.codec.EntityChange
import com.tracel.storage.codec.Scalar
import com.tracel.storage.codec.Section
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Txn
import com.tracel.storage.codec.Wchg
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.format.FormatVersion
import com.tracel.storage.format.Migration
import com.tracel.storage.Interning
import com.tracel.storage.lsm.LsmOptions
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.UUID

/**
 * 1.0 to 1.1.
 *
 * Old world history rows are read and written as new capsules.
 */
internal object WorldToCapsules {
    val STEP = Migration(FormatVersion(1, 0), FormatVersion(1, 1), groundwork = ::convert) { unit ->
        unit.delete(Tag.META[CONVERTED])
    }

    private const val CONVERTED = 3
    private const val FLUSH_ROWS = 2_000_000
    private const val BATCH_ROWS = 4096
    private const val WINDOW_MILLIS = LsmOptions.DAY_MILLIS

    private fun convert(engine: KeyValueEngine) {
        val done = engine.snapshot().use { it.get(Tag.META[CONVERTED]) != null }
        if (!done) {
            for (tag in listOf(Tag.CHRONO_CAPSULE, Tag.CHRONO_FRAGMENT, Tag.CHRONO_TRACK, Tag.CHRONO_TIME, Tag.CHRONO_MARK)) {
                deleteAll(engine, tag) { true }
            }
            capsules(engine)
            events(engine)
            items(engine)
            containers(engine)
            players(engine)
            engine.write(MutationBatch().apply { put(Tag.META[CONVERTED], byteArrayOf(1)) }, durable = true)
        }
        for (tag in listOf(Tag.WCHG, Tag.WCHG_AT, Tag.WCHG_AT_SECTION, Tag.WCHG_ENTITY)) deleteAll(engine, tag) { true }
        for (tag in listOf(Tag.EVENT, Tag.EVENT_ACTOR, Tag.EVENT_TIME, Tag.EVENT_ENTITY)) deleteAll(engine, tag) { true }
        for (tag in listOf(Tag.TXN, Tag.TXN_BY_ID, Tag.TXN_LOT, Tag.CONTAINER_SLOT, Tag.ACTOR_VISIT, Tag.ACTOR_MODE)) deleteAll(engine, tag) { true }
        for (tag in listOf(Tag.ACTOR, Tag.ITEM, Tag.TIME, Tag.SPATIAL, Tag.INTERN_REVERSE)) deleteAll(engine, tag) { true }
        engine.sync()
    }

    private fun capsules(engine: KeyValueEngine) {
        val builders = HashMap<Triple<Long, Lane, Long>, RowsBuilder>()
        var buffered = 0
        StorageUnit.open(engine).use { unit ->
            unit.scan(Tag.WCHG.prefix).use { cursor ->
                while (cursor.next()) {
                    buffered += record(cursor.keyU64(1), cursor.value(), builders)
                    if (buffered >= FLUSH_ROWS) {
                        flush(engine, builders)
                        buffered = 0
                    }
                }
            }
        }
        flush(engine, builders)
    }

    private fun events(engine: KeyValueEngine) {
        val builders = HashMap<Triple<Long, Lane, Long>, RowsBuilder>()
        StorageUnit.open(engine).use { unit ->
            val entities = HashMap<Long, UUID>()
            unit.scan(Tag.EVENT_ENTITY.prefix).use { c ->
                while (c.next()) entities[Tag.EVENT_ENTITY.long(c, 2)] = UUID(c.keyU64(1), c.keyU64(9))
            }
            var buffered = 0
            unit.scan(Tag.EVENT.prefix).use { cursor ->
                while (cursor.next()) {
                    val v = cursor.value()
                    val seq = cursor.keyU64(1)
                    val world = v.i32(5)
                    val x = v.i32(9)
                    val z = v.i32(17)
                    val millis = v.i64(21)
                    val tile = if (world == 0) Tile.of(0, 0, 0) else Tile.at(world, x, z)
                    val text = if (v.byteSize() > 29) v.asSlice(29).toArray(ValueLayout.JAVA_BYTE) else null
                    val uuid = entities[seq]
                    builders.getOrPut(Triple(tile.packed, Lane.EVENTS, Math.floorDiv(millis, WINDOW_MILLIS))) { RowsBuilder(16) }.addEntity(
                        seq, millis, if (world == 0) 0 else Cell.of(x, v.i32(13), z).packed, v.i32(1), v.i8(0).toInt(), 0,
                        uuid?.mostSignificantBits ?: 0L, uuid?.leastSignificantBits ?: 0L, text, null,
                    )
                    if (++buffered >= FLUSH_ROWS) {
                        flush(engine, builders)
                        buffered = 0
                    }
                }
            }
        }
        flush(engine, builders)
    }

    private fun items(engine: KeyValueEngine) {
        val builders = HashMap<Triple<Long, Lane, Long>, RowsBuilder>()
        val interning = Interning()
        StorageUnit.open(engine).use { unit ->
            val lots = HashMap<Long, ArrayList<FlowLot>>()
            unit.scan(Tag.TXN_LOT.prefix).use { c ->
                while (c.next()) {
                    lots.getOrPut(Tag.TXN_LOT.long(c, 0)) { ArrayList() } +=
                        FlowLot(Tag.TXN_LOT.int(c, 1), LotId(Tag.TXN_LOT.long(c, 2)), Quantity(Scalar.asLong(c.value())))
                }
            }
            var buffered = 0
            unit.scan(Tag.TXN.prefix).use { cursor ->
                while (cursor.next()) {
                    val v = cursor.value()
                    val seq = cursor.keyU64(1)
                    val flows = Txn.flowCount[v]
                    val world = Txn.world[v]
                    val millis = Txn.epochMillis[v]
                    val home = if (world == 0) Tile.of(0, 0, 0) else Tile.at(world, Txn.x[v], Txn.z[v])
                    val tiles = LinkedHashSet<Long>().apply { add(home.packed) }
                    fun touch(id: Int) {
                        if (id == 0) return
                        val pos = when (val h = runCatching { interning.resolveHolder(unit, id) }.getOrNull()) {
                            is HolderId.Block -> BlockPos(h.world, h.x, h.y, h.z)
                            is HolderId.PlacedBlock -> BlockPos(h.world, h.x, h.y, h.z)
                            else -> null
                        } ?: return
                        val w = interning.findWorldId(unit, pos.world) ?: return
                        tiles += Tile.at(w, pos.x, pos.z).packed
                    }
                    touch(Txn.causedBy[v])
                    for (i in 0 until flows) {
                        touch(Txn.source[v, i])
                        touch(Txn.destination[v, i])
                    }
                    val blobs = ItemRows.lotsByFlow(lots[seq].orEmpty(), flows)
                    val cell = if (world == 0) 0 else Cell.of(Txn.x[v], Txn.y[v], Txn.z[v]).packed
                    val window = Math.floorDiv(millis, WINDOW_MILLIS)
                    for (tile in tiles) {
                        val b = builders.getOrPut(Triple(tile, Lane.ITEMS, window)) { RowsBuilder(16) }
                        for (i in 0 until flows) {
                            b.addFlow(
                                seq, millis, cell, Txn.causedBy[v], ItemRows.kindOf(Txn.cause[v], Txn.kind[v, i]), Txn.item[v, i],
                                Txn.source[v, i], Txn.destination[v, i], i, Txn.quantity[v, i], Txn.id[v], blobs[i], home,
                            )
                            buffered++
                        }
                    }
                    if (buffered >= FLUSH_ROWS) {
                        flush(engine, builders)
                        buffered = 0
                    }
                }
            }
        }
        flush(engine, builders)
    }

    private fun containers(engine: KeyValueEngine) {
        val builders = HashMap<Triple<Long, Lane, Long>, RowsBuilder>()
        val interning = Interning()
        StorageUnit.open(engine).use { unit ->
            unit.scan(Tag.CONTAINER_SLOT.prefix).use { cursor ->
                while (cursor.next()) {
                    val v = cursor.value()
                    val holderId = Tag.CONTAINER_SLOT.int(cursor, 0)
                    val seq = Tag.CONTAINER_SLOT.long(cursor, 1)
                    val millis = ContainerSlot.epochMillis[v]
                    val count = ContainerSlot.count[v]
                    val sink = ByteWriter(8 + count * 8)
                    sink.varint(count.toLong())
                    for (i in 0 until count) {
                        sink.varint(ContainerSlot.slot[v, i].toLong())
                        sink.varint(ContainerSlot.itemKeyId[v, i].toLong())
                        sink.varint(ContainerSlot.quantity[v, i])
                    }
                    val tile = when (val h = runCatching { interning.resolveHolder(unit, holderId) }.getOrNull()) {
                        is HolderId.Block -> interning.findWorldId(unit, h.world)?.let { Tile.at(it, h.x, h.z) }
                        is HolderId.PlacedBlock -> interning.findWorldId(unit, h.world)?.let { Tile.at(it, h.x, h.z) }
                        else -> null
                    } ?: Tile.of(0, 0, 0)
                    builders.getOrPut(Triple(tile.packed, Lane.CONTAINERS, Math.floorDiv(millis, WINDOW_MILLIS))) { RowsBuilder(16) }
                        .addEntity(seq, millis, 0, holderId, 0, 0, 0L, 0L, sink.toByteArray(), null)
                }
            }
        }
        flush(engine, builders)
    }

    private fun players(engine: KeyValueEngine) {
        val builders = HashMap<Triple<Long, Lane, Long>, RowsBuilder>()
        val global = Tile.of(0, 0, 0)
        var n = 0L
        fun seqOf(millis: Long) = (millis shl 12) or (n++ and 4095)
        StorageUnit.open(engine).use { unit ->
            unit.scan(Tag.ACTOR_VISIT.prefix).use { c ->
                while (c.next()) {
                    val holder = Tag.ACTOR_VISIT.int(c, 0)
                    val start = Tag.ACTOR_VISIT.long(c, 1)
                    val end = Scalar.asLong(c.value())
                    val b = builders.getOrPut(Triple(global.packed, Lane.VISITS, Math.floorDiv(start, WINDOW_MILLIS))) { RowsBuilder(16) }
                    b.addEntity(seqOf(start), start, 0, holder, 0, 0, 0L, 0L, null, null)
                    if (end != VisitTimeline.OPEN) {
                        builders.getOrPut(Triple(global.packed, Lane.VISITS, Math.floorDiv(end, WINDOW_MILLIS))) { RowsBuilder(16) }
                            .addEntity(seqOf(end), end, 0, holder, 1, 0, 0L, 0L, null, null)
                    }
                }
            }
            unit.scan(Tag.ACTOR_MODE.prefix).use { c ->
                while (c.next()) {
                    val millis = Tag.ACTOR_MODE.long(c, 1)
                    builders.getOrPut(Triple(global.packed, Lane.MODES, Math.floorDiv(millis, WINDOW_MILLIS))) { RowsBuilder(16) }
                        .addEntity(seqOf(millis), millis, 0, Tag.ACTOR_MODE.int(c, 0), 0, Scalar.asInt(c.value()), 0L, 0L, null, null)
                }
            }
        }
        flush(engine, builders)
    }

    private fun record(seq: Long, v: MemorySegment, into: HashMap<Triple<Long, Lane, Long>, RowsBuilder>): Int {
        val world = Wchg.world[v]
        val x = Wchg.x[v]
        val y = Wchg.y[v]
        val z = Wchg.z[v]
        val millis = Wchg.epochMillis[v]
        val actor = Wchg.causedBy[v]
        val kind = ChronoKinds.of(Wchg.cause[v], Wchg.action[v])
        val window = Math.floorDiv(millis, WINDOW_MILLIS)
        fun builder(lane: Lane, tile: Tile) = into.getOrPut(Triple(tile.packed, lane, window)) { RowsBuilder(16) }
        when (Wchg.kind[v]) {
            Wchg.BLOCK -> {
                builder(Lane.BLOCKS, Tile.at(world, x, z)).add(
                    seq, millis, Cell.of(x, y, z).packed, actor, kind, BlockChange.before[v], BlockChange.after[v],
                    blob(BlockChange.beforeExtras.slice(v)), blob(BlockChange.afterExtras.slice(v)),
                )
                return 1
            }

            Wchg.SECTION -> {
                val b = builder(Lane.BLOCKS, Tile.at(world, x, z))
                val base = Section.baseSeq(v)
                val extras = Section.extrasAll(v)
                var n = 0
                Section.forEachPosition(v) { index, packed ->
                    val e = extras?.get(index)
                    b.add(
                        base + index, millis,
                        Cell.of(x + Section.positionX(packed), y + Section.positionY(packed), z + Section.positionZ(packed)).packed,
                        actor, kind, Section.before(v, index), Section.after(v, index), e?.before, e?.after,
                    )
                    n++
                }
                return n
            }

            Wchg.ENTITY -> {
                val uuid = EntityChange.entity[v]
                builder(Lane.ENTITIES, Tile.at(world, x, z)).addEntity(
                    seq, millis, Cell.of(x, y, z).packed, actor, kind, EntityChange.type[v],
                    uuid.mostSignificantBits, uuid.leastSignificantBits,
                    blob(EntityChange.beforeExtras.slice(v)), blob(EntityChange.afterExtras.slice(v)),
                )
                return 1
            }

            else -> return 0
        }
    }

    private fun blob(slice: MemorySegment): ByteArray? = if (slice.byteSize() == 0L) null else slice.toArray(ValueLayout.JAVA_BYTE)

    private fun flush(engine: KeyValueEngine, builders: HashMap<Triple<Long, Lane, Long>, RowsBuilder>) {
        val interning = Interning()
        StorageUnit.open(engine).use { unit -> flushWith(engine, unit, interning, builders) }
    }

    private fun flushWith(engine: KeyValueEngine, unit: StorageUnit, interning: Interning, builders: HashMap<Triple<Long, Lane, Long>, RowsBuilder>) {
        var batch = MutationBatch()
        var rows = 0
        for ((key, builder) in builders) {
            val (packed, lane, window) = key
            val all = builder.rows().sorted()
            var from = 0
            while (from < all.size) {
                val until = minOf(all.size, from + CAPSULE_ROWS)
                val part = if (from == 0 && until == all.size) all else all.pick(IntArray(until - from) { from + it })
                val bytes = lane.encode(part, window)
                val view = lane.open(MemorySegment.ofArray(bytes))
                file(batch, Tile(packed), lane, view, bytes, TrackIds.of(interning, unit, lane, view))
                rows += part.size
                from = until
                if (rows >= BATCH_ROWS * 16) {
                    engine.write(batch, durable = false)
                    batch = MutationBatch()
                    rows = 0
                }
            }
        }
        engine.write(batch, durable = true)
        builders.clear()
    }

    private fun file(batch: MutationBatch, tile: Tile, lane: Lane, view: CapsuleView, bytes: ByteArray, tracked: IntArray) {
        ChronoKeys.filing(tile, lane, view.firstSeq, view.lastSeq, view.millis()[0], tracked, bytes, batch::put)
    }

    private fun deleteAll(engine: KeyValueEngine, tag: Tag, doomed: (MemorySegment?) -> Boolean) {
        StorageUnit.open(engine).use { unit ->
            var batch = MutationBatch()
            unit.scan(tag.prefix).use { cursor ->
                while (cursor.next()) {
                    if (!doomed(cursor.value())) continue
                    batch.delete(cursor.key())
                    if (batch.size >= BATCH_ROWS) {
                        engine.write(batch, durable = false)
                        batch = MutationBatch()
                    }
                }
            }
            engine.write(batch, durable = true)
        }
    }

    private const val CAPSULE_ROWS = Chrono.CAPSULE_ROWS
}
