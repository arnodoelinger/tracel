package com.tracel.plugin.command.suggest

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.command.suggest.flag.FlagProfile
import com.tracel.plugin.command.suggest.flag.FlagSuggest
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.concurrent.CompletableFuture

/** Suggestions for the flags of `/tracel lookup`. */
internal object LookupSuggest : SuggestionProvider<CommandSourceStack> {
    /** Suggests a list of completions. */
    fun suggest(line: String, lists: SuggestLists): List<Suggestion> {
        val (previous, current) = splitTrailing(line)
        return FlagSuggest.complete(FlagProfile.LOOKUP, current, previous, lists)
    }

    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> = builder.reply(suggest(builder.remaining, liveLists(context.source.sender)))
}
