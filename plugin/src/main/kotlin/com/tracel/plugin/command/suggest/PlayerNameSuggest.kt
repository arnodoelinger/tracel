package com.tracel.plugin.command.suggest

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.concurrent.CompletableFuture
import org.bukkit.Bukkit

/** Players who are online, then those the server has seen, best match first. */
internal object PlayerNameSuggest : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val online = Bukkit.getOnlinePlayers().map { it.name }
        val seen = Bukkit.getOfflinePlayers().mapNotNull { it.name }.filter { it !in online }
        val names = rank(online, builder.remaining).map { Suggestion(it, tr("suggest.online")) } +
                rank(seen, builder.remaining, limit = 20).map { Suggestion(it, tr("suggest.seen")) }
        return builder.reply(names)
    }
}
