package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.suggest.RollbackSuggest
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.parsePresetted
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.usage
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel scrub` and `/tracel scrub stop`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.scrubBranch(actions: CommandActions) {
    val scrub = actions.scrub
    val store = actions.store
    literal("scrub", tr("command.scrub")) {
        requiresPermission(Permission.LOOKUP)
        executesCommand { ctx -> ctx.source.sender.usage("scrub") }
        literal("stop", tr("command.scrub_stop")) {
            executesCommand { ctx -> scrub.stop(ctx.source.sender) }
        }
        argument("flags", StringArgumentType.greedyString()) {
            suggests(RollbackSuggest)
            executesCommand { ctx ->
                val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                scrub.execute(ctx.source.sender, parsePresetted(ctx.source.sender, tokens, store))
            }
        }
    }
}
