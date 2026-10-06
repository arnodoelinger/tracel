package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel status`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.statusBranch(actions: CommandActions) {
    val status = actions.status
    literal("status", tr("command.status")) {
        requiresPermission(Permission.STATUS)
        executesCommand { ctx -> status.execute(ctx.source.sender) }
    }
}
