package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.i18n.send
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.util.requirePlayer
import org.bukkit.command.CommandSender

/** Action responsible for toggling the inspector mode for players. */
class InspectAction(private val services: TracelServices) {
    /** Toggles the inspector mode. Only for players. */
    fun execute(sender: CommandSender) {
        val player = sender.requirePlayer(tr("inspect.players_only")) ?: return
        val active = services.inspectors.toggle(player.uniqueId)

        player.send(if (active) "inspect.on" else "inspect.off")
    }
}
