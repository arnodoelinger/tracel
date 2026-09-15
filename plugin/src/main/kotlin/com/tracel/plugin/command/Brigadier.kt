package com.tracel.plugin.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

/** Creates a root or child literal command node builder. */
fun literal(
    name: String,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> =
    Commands.literal(name).apply(block)

/** Adds a child literal argument to this literal node. */
fun LiteralArgumentBuilder<CommandSourceStack>.literal(
    name: String,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> {
    val child = Commands.literal(name).apply(block)
    then(child)
    return child
}

/** Adds a child required argument to this literal node. */
fun <T : Any> LiteralArgumentBuilder<CommandSourceStack>.argument(
    name: String,
    type: ArgumentType<T>,
    block: RequiredArgumentBuilder<CommandSourceStack, T>.() -> Unit = {},
): RequiredArgumentBuilder<CommandSourceStack, T> {
    val child = Commands.argument(name, type).apply(block)
    then(child)
    return child
}

/** Adds a child literal argument to this required argument node. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.literal(
    name: String,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> {
    val child = Commands.literal(name).apply(block)
    then(child)
    return child
}

/** Checks that the command sender has the given permission node. */
fun LiteralArgumentBuilder<CommandSourceStack>.requiresPermission(permission: String) {
    requires { it.sender.hasPermission(permission) }
}

/** Sets the command execution handler, automatically returning [Command.SINGLE_SUCCESS]. */
fun LiteralArgumentBuilder<CommandSourceStack>.executesCommand(
    handler: (CommandContext<CommandSourceStack>) -> Unit,
) {
    executes { ctx ->
        handler(ctx)
        Command.SINGLE_SUCCESS
    }
}

/** Sets the command execution handler, automatically returning [Command.SINGLE_SUCCESS]. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.executesCommand(
    handler: (CommandContext<CommandSourceStack>) -> Unit,
) {
    executes { ctx ->
        handler(ctx)
        Command.SINGLE_SUCCESS
    }
}
