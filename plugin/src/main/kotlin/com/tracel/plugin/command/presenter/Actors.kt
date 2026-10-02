package com.tracel.plugin.command.presenter

import com.tracel.model.holder.HolderId
import com.tracel.plugin.mode.PlayerModes
import com.tracel.storage.ports.actor.ActorFacts
import com.tracel.storage.ports.actor.ModeTimeline
import org.bukkit.GameMode
import java.util.UUID

/**
 * What the log does not say about the ones who did things — a mob's type, a player's game mode — read from the
 * database for the lines about to be shown. One of these lives as long as one search; it is not safe to share.
 */
internal class Actors(private val facts: ActorFacts?) {
    private val kinds = HashMap<UUID, String>()
    private val modes = HashMap<UUID, ModeTimeline>()
    private val askedEntities = HashSet<UUID>()
    private val askedPlayers = HashSet<UUID>()

    /** @return the type of the mob [uuid], like `minecraft:zombie`, if the database knows it. */
    fun kind(uuid: UUID): String? = kinds[uuid]

    /** @return the mode [player] was in at [millis], if the database knows it. */
    fun mode(player: UUID, millis: Long): GameMode? = modes[player]?.at(millis)?.let(PlayerModes::mode)

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
    }

    companion object {
        /** Knows nothing: every mob is "gone", every mode unknown. */
        val NONE = Actors(null)
    }
}
