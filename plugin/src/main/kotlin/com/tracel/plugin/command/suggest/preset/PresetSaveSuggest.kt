package com.tracel.plugin.command.suggest.preset

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.command.preset.Presets
import com.tracel.plugin.command.suggest.Suggestion
import com.tracel.plugin.command.suggest.flag.FlagProfile
import com.tracel.plugin.command.suggest.flag.FlagSuggest
import com.tracel.plugin.command.suggest.liveLists
import com.tracel.plugin.command.suggest.reply
import com.tracel.plugin.command.suggest.splitTrailing
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/** The name of the preset being saved: yours to overwrite first, then a few to start from. */
internal object PresetAddNameSuggest : SuggestionProvider<CommandSourceStack> {
    private val STARTERS = listOf("grief", "creeper", "tnt", "lava", "raid")

    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val owner = (context.source.sender as? Player)?.uniqueId
        val needle = builder.remaining.lowercase()
        val mine = Presets.store?.visibleTo(owner).orEmpty()
            .filter { it.name.startsWith(needle) }
            .map { Suggestion(it.name, tr("suggest.preset_replace", "flags" to it.text)) }
        val taken = mine.map { it.text }.toSet()
        val starters = STARTERS
            .filter { it.startsWith(needle) && it !in taken }
            .map { Suggestion(it, tr("suggest.preset_name")) }
        return builder.reply(mine + starters)
    }
}

/** What goes in a preset: the flags of lookup and rollback together. */
internal object PresetAddFlagsSuggest : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val (previous, current) = splitTrailing(builder.remaining)
        return builder.reply(
            FlagSuggest.complete(
                FlagProfile.PRESET,
                current,
                previous,
                liveLists(context.source.sender)
            )
        )
    }
}

/** The presets hands out: the sender's own to share, the server's to take back. */
internal class PresetOwnedSuggest(private val server: Boolean) : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val owner = (context.source.sender as? Player)?.uniqueId
        val needle = builder.remaining.lowercase()
        val names = Presets.store?.visibleTo(owner).orEmpty()
            .filter { (it.owner == null) == server && it.name.startsWith(needle) }
            .map { Suggestion(it.name, Component.text(it.text)) }
        return builder.reply(names)
    }
}
