package com.tracel.plugin.command

import com.tracel.plugin.TracelServices
import com.tracel.storage.schema.purgeAll
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `/tracel purge confirm` wipes every table `Tracel` owns.
 */
class PurgeCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        if (args.getOrNull(1) != "confirm") {
            source.sender.sendMessage("Usage: /tracel purge confirm — wipes the entire Tracel database, with no undo.")
            return
        }

        services.scope.launch {
            withContext(services.schedulers.storage) {
                purgeAll(services.db)
                services.differ.forgetAll()
            }
            source.sender.sendMessage(
                "Tracel database purged — every lot, transaction, and rollback job is gone. " +
                    "Live world contents are untouched; run /tracel audit players to see the fresh baseline."
            )
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> =
        if (args.size == 2 && "confirm".startsWith(args[1])) listOf("confirm") else emptyList()

    override fun permission(): String = "tracel.purge"
}
