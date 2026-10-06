package com.tracel.storage.ports.actor

import com.tracel.engine.actor.ModeTimeline
import com.tracel.engine.actor.VisitTimeline
import com.tracel.model.holder.HolderId
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import java.util.*
import com.tracel.engine.actor.ActorFacts as ActorFactsPort

/**
 * What the log cannot say about who did a thing: a mob's type and a player's game mode.
 *
 * A mob's type is written once, when the mob first gets an ID (see `Interning.entityKinds`). A player's mode is a
 * timeline, one row per switch.
 */
class ActorFacts(private val storage: TracelStorage) : ActorFactsPort {
    /** Notes that [player] was in [mode] from [millis] on, unless that is the mode they were already in. */
    override suspend fun noteMode(player: UUID, mode: Int, millis: Long) {
        storage.write {
            val holder = storage.interning.internHolder(this, HolderId.Player(player))
            val latest = scan(Keys.actorModePrefix(holder)).use { cursor ->
                if (cursor.next()) Records.asInt(cursor.value()) else null
            }
            if (latest != mode) put(Keys.actorMode(holder, millis), Records.int(mode))
        }
    }

    /** Notes that [player] opened a container at [millis]; the visit stays open until [closeVisit]. */
    override suspend fun openVisit(player: UUID, millis: Long) {
        storage.write {
            val holder = storage.interning.internHolder(this, HolderId.Player(player))
            put(Keys.actorVisit(holder, millis), Records.long(VisitTimeline.OPEN))
        }
    }

    /** Notes that [player] let go of the container they had open, if they had one. */
    override suspend fun closeVisit(player: UUID, millis: Long) {
        storage.write {
            val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: return@write
            val (key, open) = scan(Keys.actorVisitPrefix(holder)).use { cursor ->
                if (cursor.next()) cursor.key() to (Records.asLong(cursor.value()) == VisitTimeline.OPEN) else return@write
            }
            if (open) put(key, Records.long(millis))
        }
    }

    /** The container visits of each of [players]; a player with none is left out. */
    override suspend fun visitsOf(players: Collection<UUID>): Map<UUID, VisitTimeline> {
        if (players.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, VisitTimeline>()
            for (player in players) {
                val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: continue
                val rows = TreeMap<Long, Long>()
                scan(Keys.actorVisitPrefix(holder)).use { cursor ->
                    while (cursor.next()) rows[Keys.invert(KeyReader.u64(cursor.key(), 5))] =
                        Records.asLong(cursor.value())
                }
                if (rows.isNotEmpty()) out[player] = VisitTimeline(rows)
            }
            out
        }
    }

    /** The mode of each of [players] over time; a player with no rows is left out. */
    override suspend fun modesOf(players: Collection<UUID>): Map<UUID, ModeTimeline> {
        if (players.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, ModeTimeline>()
            for (player in players) {
                val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: continue
                val rows = TreeMap<Long, Int>()
                scan(Keys.actorModePrefix(holder)).use { cursor ->
                    while (cursor.next()) rows[Keys.invert(KeyReader.u64(cursor.key(), 5))] =
                        Records.asInt(cursor.value())
                }
                if (rows.isNotEmpty()) out[player] = ModeTimeline(rows)
            }
            out
        }
    }

    /** The type of each of [entities] that was written down; the rest are left out. */
    override suspend fun kindsOf(entities: Collection<UUID>): Map<UUID, EntityTypeKey> {
        if (entities.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, EntityTypeKey>()
            for (entity in entities) {
                val holder = storage.interning.findHolderId(this, HolderId.Entity(entity)) ?: continue
                val record = get(Keys.actorKind(holder)) ?: continue
                out[entity] = storage.interning.resolveEntityType(this, Records.asInt(record))
            }
            out
        }
    }
}
