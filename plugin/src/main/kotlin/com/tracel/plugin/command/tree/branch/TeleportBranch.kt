package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.i18n.failed
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.*

/** `/tracel tp`: what a lookup line runs when it is clicked. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.teleportBranch(actions: CommandActions) {
    literal("tp", tr("command.tp")) {
        requiresPermission(Permission.LOOKUP)
        argument("world", StringArgumentType.word()) {
            argument("x", IntegerArgumentType.integer()) {
                argument("y", IntegerArgumentType.integer()) {
                    argument("z", IntegerArgumentType.integer()) {
                        executesCommand { ctx -> teleport(ctx) }
                    }
                }
            }
        }
    }
}

private fun teleport(ctx: CommandContext<CommandSourceStack>) {
    val player = ctx.source.sender as? Player ?: return
    val world =
        runCatching { Bukkit.getWorld(UUID.fromString(StringArgumentType.getString(ctx, "world"))) }.getOrNull()
    if (world == null) return player.failed("tp.failed", tr("tp.reason.no_world"), tr("tp.hint.no_world"))
    val at = Location(
        world,
        IntegerArgumentType.getInteger(ctx, "x") + 0.5,
        IntegerArgumentType.getInteger(ctx, "y") + 1.0,
        IntegerArgumentType.getInteger(ctx, "z") + 0.5,
        player.location.yaw,
        player.location.pitch,
    )
    player.teleportAsync(at)
}
