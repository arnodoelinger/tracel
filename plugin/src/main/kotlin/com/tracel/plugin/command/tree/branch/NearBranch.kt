package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.suggest.*
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.runNear
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.*
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel near`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.nearBranch(actions: CommandActions) {
    val lookup = actions.lookup
    val store = actions.store
    literal("near", tr("command.near")) {
        requiresPermission(Permission.LOOKUP)
        executesCommand { ctx -> runNear(ctx.source.sender, emptyList(), store, lookup) }
        argument("flags", StringArgumentType.greedyString()) {
            suggests(LookupSuggest)
            executesCommand { ctx ->
                val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                runNear(ctx.source.sender, tokens, store, lookup)
            }
        }
    }
}
