package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.util.text.tr
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel developer`: only in developer mode. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.developerBranch(actions: CommandActions) {
    val developer = actions.developer
    literal("developer", tr("command.developer")) {
        requiresPermission(Permission.DEVELOPER)

        literal("press", tr("command.developer_press")) {
            executesCommand { ctx -> developer.press(ctx.source.sender) }
        }
        literal("compact", tr("command.developer_compact")) {
            executesCommand { ctx -> developer.compact(ctx.source.sender) }
        }
        literal("settle", tr("command.developer_settle")) {
            executesCommand { ctx -> developer.settle(ctx.source.sender) }
        }
    }
}
