package com.tracel.plugin.command

import com.tracel.plugin.TracelServices
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack

/**
 * `Tracel` command.
 */
class TracelCommand(services: TracelServices) : BasicCommand {
    private val rollback = RollbackCommand(services)
    private val audit = AuditCommand(services)

    override fun execute(source: CommandSourceStack, args: Array<String>) {
        val sub = when (args.getOrNull(0)) {
            "rollback" -> rollback
            "audit" -> audit
            else -> null
        }

        if (sub == null) {
            source.sender.sendMessage("Usage: /tracel <rollback|audit> ...")
            return
        }

        @Suppress("OverrideOnly")
        if (!sub.canUse(source.sender)) {
            source.sender.sendMessage("You do not have permission for that.")
            return
        }

        sub.execute(source, args)
    }
}
