package com.tracel.plugin.command.presenter

import com.tracel.model.holder.HolderId
import com.tracel.plugin.mode.PlayerModes
import com.tracel.storage.ports.actor.ActorFacts
import com.tracel.storage.ports.actor.ModeTimeline
import com.tracel.storage.ports.actor.VisitTimeline
import org.bukkit.GameMode
import java.util.*

/** Caches what is known about the mobs and players among the holders of a lookup, so that the presenter can show it. */
internal class Actors(private val facts: ActorFacts?) {
    private val kinds = HashMap<UUID, String>()
    private val modes = HashMap<UUID, ModeTimeline>()
    private val visits = HashMap<UUID, VisitTimeline>()
    private val askedEntities = HashSet<UUID>()
    private val askedPlayers = HashSet<UUID>()

    companion object {
        val NONE = Actors(null)
    }

    /** @return the type of the mob [uuid], like `minecraft:zombie`, if the database knows it. */
    fun kind(uuid: UUID): String? = kinds[uuid]

    /** @return the mode [player] was in at [millis], if the database knows it. */
    fun mode(player: UUID, millis: Long): GameMode? = modes[player]?.at(millis)?.let(PlayerModes::mode)

    /** @return when the container visit [player] was in at [millis] began, if they were in one. */
    fun visit(player: UUID, millis: Long): Long? = visits[player]?.at(millis)

    /** Reads what is not known yet about the mobs and players among [holders]. */
    suspend fun learn(holders: Iterable<HolderId?>) {
        val facts = facts ?: return
        val entities = HashSet<UUID>()
        val players = HashSet<UUID>()
        for (holder in holders) when (holder) {
            is HolderId.Entity -> if (askedEntities.add(holder.uuid)) entities += holder.uuid
            is HolderId.Player -> if (askedPlayers.add(holder.uuid)) players += holder.uuid
            else -> Unit
        }
        for ((uuid, kind) in facts.kindsOf(entities)) kinds[uuid] = kind.value
        modes += facts.modesOf(players)
        visits += facts.visitsOf(players)
    }
}
