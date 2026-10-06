package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.suggest.*
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.parsePresetted
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.*
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel player`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.playerBranch(actions: CommandActions) {
    val store = actions.store
    val player = actions.player
    literal("player", tr("command.player")) {
        requiresPermission(Permission.LOOKUP)
        executesCommand { ctx -> ctx.source.sender.usage("player") }
        argument("player", StringArgumentType.word()) {
            suggests(PlayerNameSuggest)
            executesCommand { ctx ->
                player.execute(
                    ctx.source.sender,
                    StringArgumentType.getString(ctx, "player"),
                    ParsedLookupArgs()
                )
            }
            argument("flags", StringArgumentType.greedyString()) {
                suggests(LookupSuggest)
                executesCommand { ctx ->
                    val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                    player.execute(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "player"),
                        parsePresetted(ctx.source.sender, tokens, store),
                    )
                }
            }
        }
    }
}
