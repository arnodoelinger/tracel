package com.tracel.plugin.command.brigadier

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.tree.CommandNode
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.metrics.Telemetry
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component

/** Creates a root or child literal command node builder. [tooltip] is the text shown beside the name. */
fun literal(
    name: String,
    tooltip: Component? = null,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> = described(name, tooltip).apply(block)

/** Adds a child literal argument to this literal node. [tooltip] is the text shown beside the name. */
fun LiteralArgumentBuilder<CommandSourceStack>.literal(
    name: String,
    tooltip: Component? = null,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> {
    val child = described(name, tooltip).apply(block)
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

/** Adds a child required argument to this required argument node. */
fun <T, U : Any> RequiredArgumentBuilder<CommandSourceStack, T>.argument(
    name: String,
    type: ArgumentType<U>,
    block: RequiredArgumentBuilder<CommandSourceStack, U>.() -> Unit = {},
): RequiredArgumentBuilder<CommandSourceStack, U> {
    val child = Commands.argument(name, type).apply(block)
    then(child)
    return child
}

/** Adds a child literal argument to this required argument node. [tooltip] is the text shown beside the name. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.literal(
    name: String,
    tooltip: Component? = null,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> {
    val child = described(name, tooltip).apply(block)
    then(child)
    return child
}

/** Makes the client ask the server for the completions of these roots. */
@Suppress("UNCHECKED_CAST", "UnstableApiUsage")
fun Commands.answerSuggestionsFromServer(vararg roots: String) {
    val clientField = CommandNode::class.java.getField("clientNode")
    val copyField = CommandNode::class.java.getField("unwrappedCached")
    for (name in roots) {
        val api = dispatcher.root.getChild(name) ?: continue
        val sent = (copyField.get(api) as? CommandNode<Any>) ?: (api as CommandNode<Any>)
        val mirror = LiteralArgumentBuilder.literal<Any>(name).apply {
            sent.command?.let { executes(it) }
            requires(sent.requirement)
            then(
                RequiredArgumentBuilder.argument<Any, String>("command", StringArgumentType.greedyString())
                    .executes { Command.SINGLE_SUCCESS }
                    .suggests { _, _ -> Suggestions.empty() },
            )
        }.build()
        clientField.set(sent, mirror)
    }
}

/** Checks that the command sender has the given permission node. */
fun LiteralArgumentBuilder<CommandSourceStack>.requiresPermission(permission: Permission) {
    requires { it.sender.has(permission) }
}

/** Sets the command execution handler, automatically returning [Command.SINGLE_SUCCESS]. */
fun LiteralArgumentBuilder<CommandSourceStack>.executesCommand(
    handler: (CommandContext<CommandSourceStack>) -> Unit,
) {
    executes { ctx ->
        Telemetry.command(pathOf(ctx))
        handler(ctx)
        Command.SINGLE_SUCCESS
    }
}

/** Sets the command execution handler, automatically returning [Command.SINGLE_SUCCESS]. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.executesCommand(
    handler: (CommandContext<CommandSourceStack>) -> Unit,
) {
    executes { ctx ->
        Telemetry.command(pathOf(ctx))
        handler(ctx)
        Command.SINGLE_SUCCESS
    }
}
