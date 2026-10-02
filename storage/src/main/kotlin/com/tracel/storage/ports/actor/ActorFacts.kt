package com.tracel.storage.ports.actor

import com.tracel.model.holder.HolderId
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID

/**
 * What the log cannot say about who did a thing: a mob's type and a player's game mode.
 *
 * A mob's type is written once, when the mob first gets an ID (see `Interning.entityKinds`). A player's mode is a
 * timeline, one row per switch.
 */
class ActorFacts(private val storage: TracelStorage) {
    /** Notes that [player] was in [mode] from [millis] on, unless that is the mode they were already in. */
    suspend fun noteMode(player: UUID, mode: Int, millis: Long) {
        storage.write {
            val holder = storage.interning.internHolder(this, HolderId.Player(player))
            val latest = scan(Keys.actorModePrefix(holder)).use { cursor ->
                if (cursor.next()) Records.asInt(cursor.value()) else null
            }
            if (latest != mode) put(Keys.actorMode(holder, millis), Records.int(mode))
        }
    }

    /** The mode of each of [players] over time; a player with no rows is left out. */
    suspend fun modesOf(players: Collection<UUID>): Map<UUID, ModeTimeline> {
        if (players.isEmpty()) return emptyMap()
        return storage.read {
            val out = HashMap<UUID, ModeTimeline>()
            for (player in players) {
                val holder = storage.interning.findHolderId(this, HolderId.Player(player)) ?: continue
                val rows = TreeMap<Long, Int>()
                scan(Keys.actorModePrefix(holder)).use { cursor ->
                    while (cursor.next()) rows[Keys.invert(KeyReader.u64(cursor.key(), 5))] = Records.asInt(cursor.value())
                }
                if (rows.isNotEmpty()) out[player] = ModeTimeline(rows)
            }
            out
        }
    }

    /** The type of each of [entities] that was written down; the rest are left out. */
    suspend fun kindsOf(entities: Collection<UUID>): Map<UUID, EntityTypeKey> {
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

/** What game mode (as the code it was noted with) a player was in at any moment. */
class ModeTimeline internal constructor(private val since: NavigableMap<Long, Int>) {
    /** @return the mode at [millis], or `null` if nothing was known about the player by then. */
    fun at(millis: Long): Int? = since.floorEntry(millis)?.value
}
