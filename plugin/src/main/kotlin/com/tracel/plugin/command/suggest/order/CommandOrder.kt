package com.tracel.plugin.command.suggest.order

import com.mojang.brigadier.suggestion.Suggestions

/** The order the subcommands are listed in, most used first. */
internal object CommandOrder {
    private val LEVELS: Map<List<String>, List<String>> = mapOf(
        emptyList<String>() to listOf(
            "lookup",
            "inspect",
            "rollback",
            "near",
            "player",
            "preset",
            "status",
            "data",
            "help",
        ),
        listOf("preset") to listOf(
            "list",
            "show",
            "add",
            "delete",
            "share",
            "unshare",
        ),
    )

    /** [suggestions] for what was typed in [buffer], put in order if `/tracel` or `/tr` was where it stood. */
    fun order(buffer: String, suggestions: Suggestions): Suggestions {
        val words = buffer.trimStart().removePrefix("/").split(' ')
        if (words.size < 2 || words.first().lowercase() !in ROOTS) return suggestions
        val path = words.drop(1).dropLast(1).map { it.lowercase() }
        val ranking = LEVELS[path] ?: return suggestions
        val rank = ranking.withIndex().associate { it.value to it.index }
        val ordered = suggestions.list.filterNot { path.isEmpty() && it.text.lowercase() == "tp" }
            .sortedBy { rank[it.text.lowercase()] ?: Int.MAX_VALUE }
        return if (ordered == suggestions.list) suggestions else Suggestions(suggestions.range, ordered)
    }

    private val ROOTS = setOf("tracel", "tr")
}
