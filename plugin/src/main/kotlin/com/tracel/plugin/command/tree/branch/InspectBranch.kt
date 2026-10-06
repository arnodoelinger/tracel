package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel inspect`. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.inspectBranch(actions: CommandActions) {
    val inspect = actions.inspect
    literal("inspect", tr("command.inspect")) {
        requiresPermission(Permission.INSPECT)
        executesCommand { ctx -> inspect.execute(ctx.source.sender) }
    }
}
