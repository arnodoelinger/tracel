package com.tracel.plugin.command

import com.tracel.plugin.TracelServices
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack

/**
 * `Tracel` command.
 */
class TracelCommand(services: TracelServices) : BasicCommand {
    private val subs: Map<String, BasicCommand> = linkedMapOf(
        "rollback" to RollbackCommand(services),
        "audit" to AuditCommand(services),
        "lookup" to LookupCommand(services),
        "origin" to OriginCommand(services),
        "trace" to TraceCommand(services),
        "inspect" to InspectCommand(services),
        "purge" to PurgeCommand(services),
    )

    override fun execute(source: CommandSourceStack, args: Array<String>) {
        val sub = subs[args.getOrNull(0)]

        if (sub == null) {
            source.sender.sendMessage("Usage: /tracel <${subs.keys.joinToString("|")}> ...")
            return
        }

        @Suppress("OverrideOnly")
        if (!sub.canUse(source.sender)) {
            source.sender.sendMessage("You do not have permission for that.")
            return
        }

        sub.execute(source, args)
    }

    @Suppress("OverrideOnly")
    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
        if (args.size <= 1) {
            val partial = args.getOrNull(0) ?: ""
            return subs.filterValues { it.canUse(source.sender) }.keys.filter { it.startsWith(partial) }
        }
        val sub = subs[args.getOrNull(0)] ?: return emptyList()
        return sub.suggest(source, args)
    }
}
