package com.tracel.plugin.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.RedirectModifier
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

/** Creates a root or child literal command node builder. [tooltip] is the text shown beside the name. */
fun literal(
    name: String,
    tooltip: String? = null,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> = described(name, tooltip).apply(block)

/** Adds a child literal argument to this literal node. [tooltip] is the text shown beside the name. */
fun LiteralArgumentBuilder<CommandSourceStack>.literal(
    name: String,
    tooltip: String? = null,
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

/** Adds a child literal argument to this required argument node. [tooltip] is the text shown beside the name. */
fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.literal(
    name: String,
    tooltip: String? = null,
    block: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit = {},
): LiteralArgumentBuilder<CommandSourceStack> {
    val child = described(name, tooltip).apply(block)
    then(child)
    return child
}

/**
 * Mirrors a command node so that it reflects the updated state on the client side.
 *
 * This is necessary to ensure that suggestions or updates to the command are visible
 * to clients using the command's suggestions feature.
 */
fun CommandNode<CommandSourceStack>.answerSuggestionsFromServer() {
    val mirror = Commands.literal(name).apply {
        command?.let { executes(it) }
        requires(requirement)
        then(
            Commands.argument("command", StringArgumentType.greedyString())
                .suggests(SuggestionProvider { _, builder -> Suggestions.empty() }),
        )
    }.build()
    var type: Class<*>? = javaClass
    var field: java.lang.reflect.Field? = null
    while (type != null && field == null) {
        field = type.declaredFields.firstOrNull { it.name == "clientNode" }
        type = type.superclass
    }
    val clientNode = field ?: error("Paper CommandNode.clientNode is missing, so command tooltips cannot be sent to the client.")
    clientNode.isAccessible = true
    clientNode.set(this, mirror)
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

private fun described(name: String, tooltip: String?): LiteralArgumentBuilder<CommandSourceStack> =
    if (tooltip == null) Commands.literal(name) else TooltipLiteralBuilder(name, tooltip)

private class TooltipLiteralBuilder(
    private val literal: String,
    private val tooltip: String,
) : LiteralArgumentBuilder<CommandSourceStack>(literal) {
    override fun getThis(): LiteralArgumentBuilder<CommandSourceStack> = this

    override fun build(): LiteralCommandNode<CommandSourceStack> {
        val result = TooltipLiteralNode(
            literal,
            tooltip,
            command,
            requirement,
            redirect,
            redirectModifier,
            isFork,
        )
        for (child in arguments) result.addChild(child)
        return result
    }
}

private class TooltipLiteralNode(
    private val literalName: String,
    private val tooltip: String,
    command: Command<CommandSourceStack>?,
    requirement: Predicate<CommandSourceStack>,
    redirect: CommandNode<CommandSourceStack>?,
    modifier: RedirectModifier<CommandSourceStack>?,
    forks: Boolean,
) : LiteralCommandNode<CommandSourceStack>(literalName, command, requirement, redirect, modifier, forks) {
    override fun listSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        if (!canUse(context.source)) return Suggestions.empty()
        if (!literalName.lowercase().startsWith(builder.remainingLowerCase)) return Suggestions.empty()
        return builder.suggest(literalName, LiteralMessage(tooltip)).buildFuture()
    }

    override fun createBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        val copy = TooltipLiteralBuilder(literalName, tooltip)
        copy.requires(requirement)
        command?.let { copy.executes(it) }
        redirect?.let { copy.forward(it, redirectModifier, isFork) }
        return copy
    }
}
