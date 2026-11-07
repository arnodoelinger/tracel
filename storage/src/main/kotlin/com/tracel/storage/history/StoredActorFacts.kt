package com.tracel.storage.history

import com.tracel.engine.actor.ActorFacts
import com.tracel.engine.actor.ModeTimeline
import com.tracel.engine.actor.VisitTimeline
import com.tracel.model.holder.HolderId
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.Chrono
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.query.Sieve
import com.tracel.storage.codec.Scalar
import com.tracel.storage.codec.Tag
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** What the log cannot say about who did a thing: a mob's type and a player's game mode. */
class StoredActorFacts(storage: TracelStorage, chrono: Chrono = Chrono.of(storage)) : AbstractChronoLog(storage, chrono), ActorFacts {
    override suspend fun noteMode(player: UUID, mode: Int, millis: Long) {
        storage.write {
            val holder = storage.interning.internHolder(this, HolderId.Player(player))
            val latest = rowsOf(this, Lane.MODES, holder).lastOrNull()
            if (latest?.type != mode) {
                chrono.writer(this, Lane.MODES).addEntity(GLOBAL, nextSeq(millis), millis, 0, holder, 0, mode, 0L, 0L, null, null)
            }
        }
    }

    override suspend fun openVisit(player: UUID, millis: Long) {
        storage.write {
            val holder = storage.interning.internHolder(this, HolderId.Player(player))
            chrono.writer(this, Lane.VISITS).addEntity(GLOBAL, nextSeq(millis), millis, 0, holder, OPEN, 0, 0L, 0L, null, null)
        }
    }

    override suspend fun closeVisit(player: UUID, millis: Long) {
        storage.write {
            val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: return@write
            val latest = rowsOf(this, Lane.VISITS, holder).lastOrNull() ?: return@write
            if (latest.kind == OPEN) {
                chrono.writer(this, Lane.VISITS).addEntity(GLOBAL, nextSeq(millis), millis, 0, holder, CLOSE, 0, 0L, 0L, null, null)
            }
        }
    }

    override suspend fun visitsOf(players: Collection<UUID>): Map<UUID, VisitTimeline> {
        if (players.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, VisitTimeline>()
            for (player in players) {
                val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: continue
                val rows = TreeMap<Long, Long>()
                var open: Long? = null
                for (row in rowsOf(this, Lane.VISITS, holder)) {
                    if (row.kind == OPEN) {
                        rows[row.millis] = VisitTimeline.OPEN
                        open = row.millis
                    } else {
                        open?.let { rows[it] = row.millis }
                        open = null
                    }
                }
                if (rows.isNotEmpty()) out[player] = VisitTimeline(rows)
            }
            out
        }
    }

    override suspend fun modesOf(players: Collection<UUID>): Map<UUID, ModeTimeline> {
        if (players.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, ModeTimeline>()
            for (player in players) {
                val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: continue
                val rows = TreeMap<Long, Int>()
                for (row in rowsOf(this, Lane.MODES, holder)) rows[row.millis] = row.type
                if (rows.isNotEmpty()) out[player] = ModeTimeline(rows)
            }
            out
        }
    }

    override suspend fun kindsOf(entities: Collection<UUID>): Map<UUID, EntityTypeKey> {
        if (entities.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, EntityTypeKey>()
            for (entity in entities) {
                val holder = storage.interning.findHolderId(this, HolderId.Entity(entity)) ?: continue
                val record = get(Tag.ACTOR_KIND[holder]) ?: continue
                out[entity] = storage.interning.resolveEntityType(this, Scalar.asInt(record))
            }
            out
        }
    }

    private class Row(val seq: Long, val millis: Long, val kind: Int, val type: Int)

    private fun rowsOf(unit: StorageUnit, lane: Lane, holder: Int): List<Row> {
        val out = ArrayList<Row>()
        for (found in chrono.reader(unit).selectAnywhere(holder, Sieve.ALL, lane)) {
            val rows: Rows = found.rows
            for (r in 0 until rows.size) if (rows.actor[r] == holder) out += Row(rows.seq[r], rows.millis[r], rows.kind[r], rows.after[r])
        }
        out.sortBy { it.seq }
        return out
    }

    private fun nextSeq(millis: Long): Long = (millis shl SEQ_BITS) or (counter.incrementAndGet() and ((1L shl SEQ_BITS) - 1))

    private companion object {
        val GLOBAL = Tile.of(0, 0, 0)
        val counter = AtomicLong()
        const val SEQ_BITS = 12
        const val OPEN = 0
        const val CLOSE = 1
    }
}
