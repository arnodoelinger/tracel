package com.tracel.plugin.command

import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.WorldQuery
import com.tracel.model.id.LotId
import com.tracel.plugin.TracelServices
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit

/**
 * `/tracel rollback preview <lotId>` — plans a rollback and reports it, without applying it.
 *
 * Registered as the single `tracel` label with `rollback preview` as leading args, rather
 * than its own top-level command, so everything `Tracel` exposes stays under one namespace —
 * future subcommands (`/tracel rollback apply`, `/tracel trace`, ...) hang off the same root.
 */
class RollbackPreviewCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        if (args.getOrNull(0) != "rollback" || args.getOrNull(1) != "preview") {
            source.sender.sendMessage("Usage: /tracel rollback preview <lotId>")
            return
        }

        val lotId = args.getOrNull(2)?.toLongOrNull()
        if (lotId == null) {
            source.sender.sendMessage("Usage: /tracel rollback preview <lotId>")
            return
        }

        services.scope.launch {
            val outcome = runCatching {
                withContext(services.schedulers.storage) {
                    val worldQuery = WorldQuery { Bukkit.getPlayer(it) != null }
                    RollbackPlanner(services.repo, worldQuery).plan(listOf(LotId(lotId)))
                }
            }

            val plan = outcome.getOrNull()
            if (plan == null) {
                source.sender.sendMessage("Could not plan a rollback for lot $lotId: ${outcome.exceptionOrNull()?.message}")
                return@launch
            }

            source.sender.sendMessage(
                "Rollback plan for lot $lotId: ${plan.takeCount} take(s), ${plan.mintCount} mint(s), ${plan.unmakeCount} unmake(s)"
            )
            plan.steps.forEachIndexed { index, step -> source.sender.sendMessage(" ${index + 1}. $step") }
        }
    }

    override fun permission(): String = "tracel.rollback"
}
