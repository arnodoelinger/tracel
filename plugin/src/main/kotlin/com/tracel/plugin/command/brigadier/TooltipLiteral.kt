package com.tracel.plugin.command.brigadier

import com.mojang.brigadier.Command
import com.mojang.brigadier.RedirectModifier
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.tracel.plugin.i18n.asMessage
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate
import net.kyori.adventure.text.Component

internal fun pathOf(ctx: CommandContext<CommandSourceStack>): String =
    ctx.nodes.mapNotNull { (it.node as? LiteralCommandNode<*>)?.literal }.drop(1).joinToString(" ").ifEmpty { "help" }

internal fun described(name: String, tooltip: Component?): LiteralArgumentBuilder<CommandSourceStack> =
    if (tooltip == null) Commands.literal(name) else TooltipLiteralBuilder(name, tooltip)

private class TooltipLiteralBuilder(
    private val literal: String,
    private val tooltip: Component,
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
    private val tooltip: Component,
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
        return builder.suggest(literalName, tooltip.asMessage()).buildFuture()
    }

    override fun createBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        val copy = TooltipLiteralBuilder(literalName, tooltip)
        copy.requires(requirement)
        command?.let { copy.executes(it) }
        redirect?.let { copy.forward(it, redirectModifier, isFork) }
        return copy
    }
}
