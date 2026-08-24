package com.tracel.plugin.command

import com.tracel.engine.provenance.FlowGraph
import com.tracel.plugin.TracelServices
import com.tracel.plugin.lookup.renderOrigin
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch

/**
 * `/tracel origin hand [player]` / `/tracel origin block <x> <y> <z> <material> [world]`.
 */
class OriginCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        val sender = source.sender

        services.scope.launch {
            val target = resolveProvenanceTarget(services, sender, args)
            if (target == null) {
                sender.sendMessage(provenanceUsage("origin"))
                return@launch
            }
            val (holder, itemKey) = target

            services.atomically {
                val lots = services.repo.accountQueue(holder, itemKey)
                if (lots.isEmpty()) {
                    sender.sendMessage("Nothing tracked for ${itemKey.material} there.")
                    return@atomically
                }
                val graph = FlowGraph(services.repo)
                for ((_, lot) in lots) {
                    renderOrigin(graph.originOf(lot.id)).forEach(sender::sendMessage)
                }
            }
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> =
        suggestProvenanceTarget(source.sender, args)

    override fun permission(): String = "tracel.origin"
}
