package com.tracel.plugin.command.preset

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.command.suggest.Suggestion
import com.tracel.plugin.command.suggest.reply
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/** The names of the presets the sender can use, each with what it holds. */
internal object PresetNameSuggest : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val owner = (context.source.sender as? Player)?.uniqueId
        val needle = builder.remaining.lowercase()
        val names = Presets.store?.visibleTo(owner).orEmpty()
            .filter { it.name.startsWith(needle) }
            .map { Suggestion(it.name, it.text) }
        return builder.reply(names)
    }
}
