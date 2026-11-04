package com.tracel.plugin.command.action

import com.tracel.plugin.bukkit.command.requirePlayer
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.text.send
import com.tracel.plugin.util.text.tr
import org.bukkit.command.CommandSender

/** Action responsible for toggling the inspector mode for players. */
class InspectAction(private val services: TracelServices) {
    /** Toggles the inspector mode. Only for players. */
    fun execute(sender: CommandSender) {
        val player = sender.requirePlayer(tr("inspect.players_only")) ?: return
        val active = services.listeners.inspectors.toggle(player.uniqueId)

        player.send(if (active) "inspect.on" else "inspect.off")
    }
}
