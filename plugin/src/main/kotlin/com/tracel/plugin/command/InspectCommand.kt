package com.tracel.plugin.command

import com.tracel.plugin.TracelServices
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * `/tracel inspect` toggles the sender's inspector mode. See
 * [com.tracel.plugin.listener.inspect.InspectListener] for what clicking a block does while it's on.
 */
class InspectCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        val player = source.sender as? Player
        if (player == null) {
            source.sender.sendMessage("Only players can toggle inspect mode.")
            return
        }

        val active = services.inspectors.toggle(player.uniqueId)
        player.sendMessage(
            if (active) {
                "Inspector mode on. Click any block or container to see its history in chat."
            } else {
                "Inspector mode off."
            }
        )
    }

    override fun permission(): String = "tracel.inspect"
}
