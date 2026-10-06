package com.tracel.plugin.command.tree

import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.brigadier.answerSuggestionsFromServer
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.suggest.*
import com.tracel.plugin.command.tree.branch.dataBranch
import com.tracel.plugin.command.tree.branch.helpBranch
import com.tracel.plugin.command.tree.branch.inspectBranch
import com.tracel.plugin.command.tree.branch.lookupBranch
import com.tracel.plugin.command.tree.branch.nearBranch
import com.tracel.plugin.command.tree.branch.playerBranch
import com.tracel.plugin.command.tree.branch.presetBranch
import com.tracel.plugin.command.tree.branch.rollbackBranch
import com.tracel.plugin.command.tree.branch.statusBranch
import com.tracel.plugin.command.tree.branch.teleportBranch
import com.tracel.plugin.i18n.*
import com.tracel.plugin.services.TracelServices
import io.papermc.paper.command.brigadier.Commands

/** `Tracel` commands. */
object TracelCommand {
    /** Registers all `Tracel` commands. */
    fun register(registrar: Commands, services: TracelServices) {
        val actions = CommandActions(services)

        val root = literal("tracel") {
            executesCommand { ctx -> sendHelp(ctx.source.sender, services.plugin) }

            helpBranch(actions)
            rollbackBranch(actions)
            lookupBranch(actions)
            teleportBranch(actions)
            presetBranch(actions)
            nearBranch(actions)
            playerBranch(actions)
            statusBranch(actions)
            inspectBranch(actions)
            dataBranch(actions)
        }

        registrar.register(root.build(), "Tracel commands.", listOf("tr"))
        registrar.answerSuggestionsFromServer("tracel", "tr")
    }
}
