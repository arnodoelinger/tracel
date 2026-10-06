package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.suggest.*
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.parsePresetted
import com.tracel.plugin.command.tree.refresh
import com.tracel.plugin.command.tree.rerunnable
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.*
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel lookup`: a search, a page turn, a refresh or an export. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.lookupBranch(actions: CommandActions) {
    val lookup = actions.lookup
    val store = actions.store
    val export = actions.export
    literal("lookup", tr("command.lookup")) {
        requiresPermission(Permission.LOOKUP)
        executesCommand { ctx -> LookupPresenter.usage(ctx.source.sender) }
        argument("flags", StringArgumentType.greedyString()) {
            suggests(LookupSuggest)
            executesCommand { ctx ->
                val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                if ("#export" in tokens) return@executesCommand lookup.export(ctx.source.sender)
                if ("#refresh" in tokens) return@executesCommand refresh(
                    ctx.source.sender,
                    tokens,
                    store,
                    lookup
                )
                val turned = tokens.singleOrNull()?.takeIf { it.startsWith("p:") || it.startsWith("page:") }
                    ?.substringAfter(':')?.toIntOrNull()
                if (turned != null && turned >= 1) lookup.turn(ctx.source.sender, turned)
                else lookup.execute(
                    ctx.source.sender,
                    parsePresetted(ctx.source.sender, tokens, store).rerunnable("lookup", tokens)
                )
            }
        }
    }
}
