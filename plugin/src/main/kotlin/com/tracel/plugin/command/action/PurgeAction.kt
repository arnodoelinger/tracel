package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.storage.ports.ops.purgeAll
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender

/** Action responsible for safely purging the `Tracel` database. */
class PurgeAction(private val services: TracelServices) {
    /** Deletes all `Tracel`'s history. */
    fun execute(sender: CommandSender) {
        if (services.composite.isRunning) {
            sender.sendMessage("Tracel: a rollback is running — wait for it to finish, then purge.")
            return
        }

        // TODO: add more guards...

        services.scope.launch {
            purgeAll(services.storage)
            services.repo.forget()
            services.differ.forgetAll()
            sender.sendMessage("Tracel history purged.")
        }
    }
}
