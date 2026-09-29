package com.tracel.plugin.command.suggest

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/** Players who are online, then those the server has seen, best match first. */
internal object PlayerNameSuggest : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val online = Bukkit.getOnlinePlayers().map { it.name }
        val seen = Bukkit.getOfflinePlayers().mapNotNull { it.name }.filter { it !in online }
        val names = rank(online, builder.remaining).map { Suggestion(it, "Online") } +
                rank(seen, builder.remaining, limit = 20).map { Suggestion(it, "Seen before") }
        return builder.reply(names)
    }
}
