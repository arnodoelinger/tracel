package com.tracel.plugin.listener.session

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent

/** `/give`, `/clear`, `/item`: listener. */
class GapCommandListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) = touched(event.message.removePrefix("/"))

    @Observes
    fun onServerCommand(event: ServerCommandEvent) = touched(event.command.removePrefix("/"))

    @Observes
    fun onRemoteCommand(event: RemoteServerCommandEvent) = touched(event.command.removePrefix("/"))

    private fun touched(line: String) {
        val tokens = line.trim().split(Regex("\\s+"))
        val name = tokens.firstOrNull()?.lowercase()?.removePrefix("minecraft:") ?: return
        if (name !in GAP_COMMANDS) return
        val target = tokens.getOrNull(1)
        val players = target?.takeIf { !it.startsWith("@") }?.let { Bukkit.getPlayerExact(it) }?.let(::listOf)
            ?: Bukkit.getOnlinePlayers().toList()
        for (player in players) settle(player)
    }

    private fun settle(player: Player) {
        for (delay in SETTLE_TICKS) {
            later(player, delay) { material.reconcile(listOf(player.inventory), player, CauseKind.WORLD, anonymous = true) }
        }
    }

    private companion object {
        val GAP_COMMANDS = setOf("give", "clear", "item")
        val SETTLE_TICKS = longArrayOf(1L, 5L, 20L)
    }
}
