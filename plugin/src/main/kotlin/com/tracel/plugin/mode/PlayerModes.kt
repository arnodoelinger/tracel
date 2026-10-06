package com.tracel.plugin.mode

import com.tracel.annotations.Observes
import com.tracel.engine.actor.ActorFacts
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent

/** Writes down what game mode each player is in, and when it changes. */
internal class PlayerModes(private val writes: ActorWrites, private val facts: ActorFacts) : Listener {
    /** Marks the players online right now, for a server that was reloaded under them. */
    fun noteOnline() = Bukkit.getOnlinePlayers().forEach(::note)

    @Observes(ignoreCancelled = false)
    fun onJoin(event: PlayerJoinEvent) = note(event.player)

    @Observes
    fun onChange(event: PlayerGameModeChangeEvent) = note(event.player, event.newGameMode)

    private fun note(player: Player, mode: GameMode = player.gameMode) {
        val uuid = player.uniqueId
        val millis = System.currentTimeMillis()
        writes.submit { facts.noteMode(uuid, code(mode), millis) }
    }

    companion object {
        fun code(mode: GameMode): Int = when (mode) {
            GameMode.SURVIVAL -> 0
            GameMode.CREATIVE -> 1
            GameMode.ADVENTURE -> 2
            GameMode.SPECTATOR -> 3
        }

        fun mode(code: Int): GameMode? = when (code) {
            0 -> GameMode.SURVIVAL
            1 -> GameMode.CREATIVE
            2 -> GameMode.ADVENTURE
            3 -> GameMode.SPECTATOR
            else -> null
        }
    }
}
