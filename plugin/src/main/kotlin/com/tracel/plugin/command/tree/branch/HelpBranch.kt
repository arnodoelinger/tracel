package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.sendHelp
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel help`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.helpBranch(actions: CommandActions) {
    literal("help", tr("command.help")) {
        executesCommand { ctx -> sendHelp(ctx.source.sender, actions.services.plugin) }
    }
}
