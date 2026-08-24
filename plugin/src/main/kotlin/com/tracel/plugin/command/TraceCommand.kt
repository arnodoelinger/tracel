package com.tracel.plugin.command

import com.tracel.engine.provenance.FlowGraph
import com.tracel.plugin.TracelServices
import com.tracel.plugin.lookup.renderFate
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch

/**
 * `/tracel trace hand [player]` / `/tracel trace block <x> <y> <z> <material> [world]` is like an
 * answer on question "what happened to this item", walking [FlowGraph.fateOf] forward to whatever
 * is still live or a sink.
 *
 * See [resolveProvenanceTarget] for what a target resolves to.
 */
class TraceCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        val sender = source.sender

        services.scope.launch {
            val target = resolveProvenanceTarget(services, sender, args)
            if (target == null) {
                sender.sendMessage(provenanceUsage("trace"))
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
                    renderFate(graph.fateOf(lot.id)).forEach(sender::sendMessage)
                }
            }
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> =
        suggestProvenanceTarget(source.sender, args)

    override fun permission(): String = "tracel.trace"
}
