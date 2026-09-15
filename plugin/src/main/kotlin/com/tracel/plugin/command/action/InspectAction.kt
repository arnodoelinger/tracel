package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.util.requirePlayer
import org.bukkit.command.CommandSender

/** Action responsible for toggling the inspector mode for players. */
class InspectAction(private val services: TracelServices) {
    /** Toggles the inspector mode. Only for players. */
    fun execute(sender: CommandSender) {
        val player = sender.requirePlayer("Only players can toggle inspect mode.") ?: return
        val active = services.inspectors.toggle(player.uniqueId)

        player.sendMessage(
            if (active) {
                "Inspector mode on. Click any block or container to see its history in chat."
            } else {
                "Inspector mode off."
            }
        )
    }
}
