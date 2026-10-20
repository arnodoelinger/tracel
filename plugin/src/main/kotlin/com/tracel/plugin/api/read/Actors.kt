package com.tracel.plugin.api.read

import com.tracel.api.history.ActorFacts
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.ItemFlow
import com.tracel.api.actor.Actor
import com.tracel.api.item.ItemHolder
import com.tracel.api.world.NamespacedName
import com.tracel.engine.actor.ModeTimeline
import com.tracel.engine.actor.VisitTimeline
import com.tracel.plugin.actor.PlayerModes
import org.bukkit.GameMode
import java.time.Instant
import java.util.UUID
import com.tracel.engine.actor.ActorFacts as StoredFacts

/**
 * Caches what is known about the mobs and players among the records of a lookup,
 * so that the presenter can show it.
 */
internal class Actors(private val facts: StoredFacts?) : ActorFacts {
    private val kinds = HashMap<UUID, String>()
    private val modes = HashMap<UUID, ModeTimeline>()
    private val visits = HashMap<UUID, VisitTimeline>()
    private val askedEntities = HashSet<UUID>()
    private val askedPlayers = HashSet<UUID>()

    companion object {
        val NONE = Actors(null)
    }

    override fun entityType(uuid: UUID): NamespacedName? = kinds[uuid]?.let(NamespacedName::parse)

    override fun mode(player: UUID, at: Instant): GameMode? =
        modes[player]?.at(at.toEpochMilli())?.let(PlayerModes::mode)

    override fun visitStart(player: UUID, at: Instant): Instant? =
        visits[player]?.at(at.toEpochMilli())?.let(Instant::ofEpochMilli)

    override suspend fun learn(records: Collection<HistoryRecord>) {
        val facts = facts ?: return
        val entities = HashSet<UUID>()
        val players = HashSet<UUID>()
        fun note(actor: Actor?) {
            when (actor) {
                is Actor.Entity -> if (askedEntities.add(actor.uuid)) entities += actor.uuid
                is Actor.Player -> if (askedPlayers.add(actor.uuid)) players += actor.uuid
                else -> Unit
            }
        }

        fun note(holder: ItemHolder) {
            when (holder) {
                is ItemHolder.Entity -> if (askedEntities.add(holder.uuid)) entities += holder.uuid
                is ItemHolder.Player -> if (!holder.stash && askedPlayers.add(holder.uuid)) players += holder.uuid
                else -> Unit
            }
        }
        for (record in records) {
            note(record.actor)
            if (record is ItemFlow) {
                note(record.from)
                note(record.to)
            }
        }
        for ((uuid, kind) in facts.kindsOf(entities)) kinds[uuid] = kind.value
        modes += facts.modesOf(players)
        visits += facts.visitsOf(players)
    }
}
