package com.tracel.plugin.mode

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/** What game mode each player was in, and since when. */
internal object PlayerModes {
    private val timeline = ConcurrentHashMap<UUID, ConcurrentSkipListMap<Long, GameMode>>()
    private var file: File? = null

    /** Reads [store] and, from then on, appends to it; the players online now are marked as they are. */
    fun open(store: File) {
        file = store
        if (store.isFile) runCatching {
            store.forEachLine { line ->
                val (uuid, millis, mode) = line.split(' ').takeIf { it.size == 3 } ?: return@forEachLine
                timeline.getOrPut(UUID.fromString(uuid)) { ConcurrentSkipListMap() }[millis.toLong()] = GameMode.valueOf(mode)
            }
        }
        Bukkit.getOnlinePlayers().forEach { note(it.uniqueId, it.gameMode) }
    }

    /** The mode [player] was in at [millis], or `null` if nothing was known about them by then. */
    fun at(player: UUID, millis: Long): GameMode? = timeline[player]?.floorEntry(millis)?.value

    /** Records that [player] was in [mode] at [millis], unless the last record for them is the same. */
    fun note(player: UUID, mode: GameMode, millis: Long = System.currentTimeMillis()) {
        val own = timeline.getOrPut(player) { ConcurrentSkipListMap() }
        if (own.lastEntry()?.value == mode) return
        own[millis] = mode
        runCatching { synchronized(this) { file?.appendText("$player $millis ${mode.name}\n") } }
    }

    class GameModeListener : Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: PlayerJoinEvent) = note(event.player.uniqueId, event.player.gameMode)

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onChange(event: PlayerGameModeChangeEvent) = note(event.player.uniqueId, event.newGameMode)
    }
}
