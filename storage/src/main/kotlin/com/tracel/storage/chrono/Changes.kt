package com.tracel.storage.chrono

import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.Interning
import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.bits.RadixSort
import com.tracel.storage.chrono.query.Found
import com.tracel.storage.codec.Extras
import java.util.*

/** Rows back into the model, every ID resolved once per unit. */
internal class Changes(private val interning: Interning, private val unit: StorageUnit) {
    private val worlds = HashMap<Int, WorldId>()
    private val holders = HashMap<Int, HolderId>()
    private val blocks = HashMap<Int, BlockDataKey>()
    private val plain = HashMap<Int, BlockShape>()
    private val types = HashMap<Int, EntityTypeKey>()

    fun newestFirst(found: List<Pair<Lane, Found>>, offset: Int, limit: Int): List<WorldChange> {
        val total = found.sumOf { it.second.rows.size }
        val seqs = LongArray(total)
        val refs = LongArray(total)
        var n = 0
        for ((fi, f) in found.withIndex()) {
            val rows = f.second.rows
            for (r in 0 until rows.size) {
                seqs[n] = rows.seq[r]
                refs[n] = (fi.toLong() shl 32) or r.toLong()
                n++
            }
        }
        val order = RadixSort.ascending(seqs, n)
        val from = offset.coerceAtMost(n)
        val to = if (limit == Int.MAX_VALUE) n else (from.toLong() + limit).coerceAtMost(n.toLong()).toInt()
        val out = ArrayList<WorldChange>(to - from)
        for (k in from until to) {
            val ref = refs[order[n - 1 - k]]
            val (lane, f) = found[(ref ushr 32).toInt()]
            out += change(lane, f.tile, f.rows, ref.toInt())
        }
        return out
    }

    fun change(lane: Lane, tile: Tile, rows: Rows, r: Int): WorldChange {
        val kind = rows.kind[r]
        val action = ChronoKinds.action(kind)
        val at = pos(tile, rows.cell[r])
        val subject = when (lane) {
            Lane.BLOCKS -> ChangeSubject.Block(
                shape(rows.before[r], rows.beforeExtrasAt(r)),
                shape(rows.after[r], rows.afterExtrasAt(r))
            )

            Lane.ENTITIES -> {
                val type = types.getOrPut(rows.after[r]) { interning.resolveEntityType(unit, rows.after[r]) }
                val before = rows.beforeExtrasAt(r)?.let { Extras.decodeShape(type, at, it) }
                    ?: if (action == ActionKind.ENTITY_SPAWN) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                val after = rows.afterExtrasAt(r)?.let { Extras.decodeShape(type, at, it) }
                    ?: if (action == ActionKind.ENTITY_REMOVE) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                ChangeSubject.Entity(UUID(rows.uuidHigh?.get(r) ?: 0L, rows.uuidLow?.get(r) ?: 0L), type, before, after)
            }

            else -> error("a row of the ${lane.name.lowercase()} lane is not a world change.")
        }
        val actor = rows.actor[r]
        return WorldChange(
            Seq(rows.seq[r]), action, ChronoKinds.cause(kind),
            if (actor == 0) null else holders.getOrPut(actor) { interning.resolveHolder(unit, actor) },
            rows.millis[r], at, subject,
        )
    }

    fun pos(tile: Tile, cell: Int): BlockPos {
        val c = Cell(cell)
        return BlockPos(
            worlds.getOrPut(tile.world) { interning.resolveWorld(unit, tile.world) },
            tile.originX + c.x,
            c.y,
            tile.originZ + c.z
        )
    }

    fun shape(id: Int, extras: ByteArray?): BlockShape {
        if (extras == null || extras.isEmpty()) return plain.getOrPut(id) { BlockShape(data(id)) }
        return BlockShape(data(id), Extras.decodeBlock(extras))
    }

    fun data(id: Int): BlockDataKey = blocks.getOrPut(id) { interning.resolveBlockData(unit, id) }
}
