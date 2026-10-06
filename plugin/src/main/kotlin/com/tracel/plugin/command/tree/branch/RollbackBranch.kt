package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.suggest.*
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.parsePresetted
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.*
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel rollback` and `/tracel rollback undo`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.rollbackBranch(actions: CommandActions) {
    val rollback = actions.rollback
    val undo = actions.undo
    val store = actions.store
    literal("rollback", tr("command.rollback")) {
        requiresPermission(Permission.ROLLBACK)
        executesCommand { ctx -> RollbackPresenter.usage(ctx.source.sender) }
        argument("flags", StringArgumentType.greedyString()) {
            suggests(RollbackSuggest)
            executesCommand { ctx ->
                val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                rollback.execute(ctx.source.sender, parsePresetted(ctx.source.sender, tokens, store))
            }
        }

        literal("undo", tr("command.undo")) {
            takeBack(undo)
        }
    }
}

private fun LiteralArgumentBuilder<CommandSourceStack>.takeBack(action: UndoAction) {
    executesCommand { ctx -> action.execute(ctx.source.sender) }
    literal("#confirm", tr("command.undo_confirm")) {
        executesCommand { ctx -> action.execute(ctx.source.sender, confirmed = true) }
    }
    argument("job", LongArgumentType.longArg(1)) {
        executesCommand { ctx -> action.execute(ctx.source.sender, job = LongArgumentType.getLong(ctx, "job")) }
        literal("#confirm", tr("command.undo_confirm")) {
            executesCommand { ctx ->
                action.execute(ctx.source.sender, confirmed = true, job = LongArgumentType.getLong(ctx, "job"))
            }
        }
    }
}
