package com.tracel.plugin.command.suggest

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.command.args.PurgeArgument
import com.tracel.plugin.command.suggest.support.ago
import com.tracel.plugin.i18n.tr
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/** Suggestions for the words of `/tracel data purge`: what is left to say, then the values the last word takes. */
internal object PurgeSuggest : SuggestionProvider<CommandSourceStack> {
    private val SPANS = listOf(1L to "day", 7L to "day", 14L to "day", 30L to "day", 90L to "day")
    private val UNITS = listOf("h" to "hour", "d" to "day", "w" to "week")

    /** Suggests a list of completions. */
    fun suggest(line: String, lists: SuggestLists, known: List<String> = emptyList()): List<Suggestion> {
        val (previous, current) = splitTrailing(line)
        return when (previous.lastOrNull()?.lowercase()) {
            PurgeArgument.CATEGORY -> categories(current)
            PurgeArgument.OLDER -> spans(current)
            PurgeArgument.WORLD -> rank(lists.worldNames, current).map {
                Suggestion(
                    it,
                    tr("suggest.world", "name" to it)
                )
            }

            PurgeArgument.PLAYER -> {
                val online = rank(lists.onlinePlayers, current).map { Suggestion(it, tr("suggest.online")) }
                online + rank(known.filter { it !in lists.onlinePlayers }, current, limit = 20)
                    .map { Suggestion(it, tr("suggest.seen")) }
            }

            else -> words(previous, current)
        }
    }

    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val known = Bukkit.getOfflinePlayers().mapNotNull { it.name }
        return builder.reply(suggest(builder.remaining, liveLists(context.source.sender), known))
    }

    private fun words(previous: List<String>, current: String): List<Suggestion> {
        val used = previous.map { it.lowercase() }.toSet()
        val out = ArrayList<Suggestion>()
        for (keyword in PurgeArgument.KEYWORDS) {
            if (keyword !in used) out += Suggestion(keyword, tr("purge.suggest.$keyword"))
        }
        if (previous.isEmpty()) out += Suggestion(PurgeArgument.ALL, tr("purge.suggest.all"))
        if (previous.isNotEmpty() && PurgeArgument.ALL !in used && PurgeArgument.CONFIRM !in used) {
            out += Suggestion(PurgeArgument.CONFIRM, tr("purge.suggest.confirm"))
        }
        val order = out.map { it.text }
        return rank(order, current).map { name -> out.first { it.text == name } }
    }

    private fun categories(current: String): List<Suggestion> {
        val done = current.substringBeforeLast(',', "")
        val prefix = if (done.isEmpty()) "" else "$done,"
        val taken = done.split(',').map { it.lowercase() }.toSet()
        val left = PurgeArgument.CATEGORIES.keys.filter { it !in taken }
        return rank(left, current.substringAfterLast(',')).map { name ->
            Suggestion("$prefix$name", tr("purge.suggest.category.$name"), tail = name)
        }
    }

    private fun spans(current: String): List<Suggestion> {
        if (current.isNotEmpty() && current.all { it.isDigit() }) {
            val amount = current.toLongOrNull() ?: return emptyList()
            return UNITS.map { (unit, noun) -> Suggestion("$current$unit", ago(amount, noun), tail = unit) }
        }
        val names = SPANS.map { (amount, noun) -> "${amount}${noun.first()}" to ago(amount, noun) }
        val byName = names.toMap()
        return rank(names.map { it.first }, current).map { Suggestion(it, byName[it]) }
    }
}
