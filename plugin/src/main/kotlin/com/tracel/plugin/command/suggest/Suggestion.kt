package com.tracel.plugin.command.suggest

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.command.args.ActionArgument
import com.tracel.plugin.dialog.VANILLA_BLOCK_NAMES
import com.tracel.plugin.dialog.VANILLA_ITEM_NAMES
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/**
 * One tab entry.
 *
 * - [text] is the whole token
 * - [tail] is offered on its own after the part already typed
 */
internal data class Suggestion(
    val text: String,
    val tooltip: String? = null,
    val tail: String? = null,
)

/** Names a suggester can offer. Gathered once per tab press. */
internal data class SuggestLists(
    val onlinePlayers: List<String> = emptyList(),
    val worldNames: List<String> = emptyList(),
    val actionNames: List<String> = emptyList(),
    val itemNames: List<String> = emptyList(),
    val blockNames: List<String> = emptyList(),
)

/**
 * Generates a [SuggestLists] instance containing names of online players, world names, action names,
 * item names, and block names to be used for suggestion purposes.
 */
internal fun liveLists(): SuggestLists = SuggestLists(
    onlinePlayers = Bukkit.getOnlinePlayers().map { it.name },
    worldNames = Bukkit.getWorlds().map { it.name },
    actionNames = ActionArgument.NAMES,
    itemNames = VANILLA_ITEM_NAMES,
    blockNames = VANILLA_BLOCK_NAMES,
)

/** The token under the cursor, and the tokens already finished before it. */
internal fun splitTrailing(line: String): Pair<List<String>, String> {
    val lastSpace = line.lastIndexOf(' ')
    if (lastSpace < 0) return emptyList<String>() to line
    val previous = line.substring(0, lastSpace).split(' ').filter { it.isNotBlank() }
    return previous to line.substring(lastSpace + 1)
}

/**
 * Offers [suggestions] for the token being typed.
 * A suggestion with a [Suggestion.tail] is anchored after the prefix, so `100` offers `b` and `c`.
 */
internal fun SuggestionsBuilder.reply(suggestions: List<Suggestion>): CompletableFuture<Suggestions> {
    if (suggestions.isEmpty()) return Suggestions.empty()

    val lastSpace = remaining.lastIndexOf(' ')
    val tokenStart = start + if (lastSpace >= 0) lastSpace + 1 else 0
    val grouped = suggestions.groupBy { placement(it, tokenStart).first }

    val built = grouped.map { (offset, group) ->
        val token = createOffset(offset)
        for (suggestion in group) {
            val insert = placement(suggestion, tokenStart).second
            val tooltip = suggestion.tooltip
            if (tooltip != null) token.suggest(insert, LiteralMessage(tooltip))
            else token.suggest(insert)
        }
        token.build()
    }
    val merged = if (built.size == 1) built.first() else Suggestions.merge(input, built)
    return CompletableFuture.completedFuture(merged)
}

/** Filters and ranks a list of names based on their similarity to the raw input string. */
internal fun rank(names: List<String>, raw: String, limit: Int = Int.MAX_VALUE): List<String> {
    val needle = raw.lowercase()
    if (needle.isEmpty()) return names.take(limit)
    return names
        .mapNotNull { name ->
            val lower = name.lowercase()
            val score = when {
                lower == needle -> 0
                lower.startsWith(needle) -> 1
                lower.contains(needle) -> 2
                else -> return@mapNotNull null
            }
            score to name
        }
        .sortedWith(compareBy({ it.first }, { it.second.length }, { it.second }))
        .take(limit)
        .map { it.second }
}

private fun SuggestionsBuilder.placement(suggestion: Suggestion, tokenStart: Int): Pair<Int, String> {
    val tail = suggestion.tail
    if (tail != null && suggestion.text.endsWith(tail)) {
        val at = tokenStart + suggestion.text.length - tail.length
        if (at in tokenStart..input.length) return at to tail
    }
    return tokenStart to suggestion.text
}
