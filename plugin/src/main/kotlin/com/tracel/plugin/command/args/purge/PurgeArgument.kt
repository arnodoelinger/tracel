package com.tracel.plugin.command.args.purge

import com.tracel.engine.store.PurgeCategory
import com.tracel.plugin.command.args.time.TimeArgument
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component

internal object PurgeArgument {
    const val CATEGORY = "category"
    const val OLDER = "older"
    const val WORLD = "world"
    const val PLAYER = "player"
    const val ALL = "all"
    const val CONFIRM = "#confirm"

    val KEYWORDS: List<String> = listOf(CATEGORY, OLDER, WORLD, PLAYER)

    val CATEGORIES: Map<String, PurgeCategory> = PurgeCategory.entries.associateBy { it.name.lowercase() }

    fun parse(tokens: List<String>): PurgeArgs {
        var args = PurgeArgs()
        val errors = ArrayList<Component>()
        val seen = HashSet<String>()
        var at = 0
        while (at < tokens.size) {
            val token = tokens[at++]
            val word = token.lowercase()
            when (word) {
                CONFIRM -> args = args.copy(confirmed = true)
                ALL -> args = args.copy(everything = true)
                CATEGORY, OLDER, WORLD, PLAYER -> {
                    val value = tokens.getOrNull(at++)
                    if (value == null || value.lowercase() in KEYWORDS || value == CONFIRM) {
                        errors += tr("purge.reason.needs_value", "flag" to word)
                        if (value != null) at--
                        continue
                    }
                    if (!seen.add(word)) errors += tr("purge.reason.twice", "flag" to word)
                    args = when (word) {
                        CATEGORY -> categories(value, errors)?.let { args.copy(categories = args.categories + it) }
                            ?: args

                        OLDER -> span(value, errors)?.let { args.copy(olderMillis = it) } ?: args
                        WORLD -> args.copy(world = value)
                        else -> args.copy(player = value)
                    }
                }

                else -> errors += tr("common.invalid", "token" to token)
            }
        }
        if (args.everything && (args.categories.isNotEmpty() || args.olderMillis != null || args.world != null || args.player != null)) {
            errors += tr("purge.reason.all_alone")
        }
        return args.copy(errors = errors)
    }

    private fun categories(value: String, errors: MutableList<Component>): Set<PurgeCategory>? {
        val found = LinkedHashSet<PurgeCategory>()
        for (name in value.split(',').filter { it.isNotBlank() }) {
            val category = CATEGORIES[name.lowercase()]
            if (category == null) errors += tr("purge.reason.unknown_category", "name" to name) else found += category
        }
        return found.takeIf { it.isNotEmpty() }
    }

    private fun span(value: String, errors: MutableList<Component>): Long? {
        val millis = TimeArgument.parseDuration(value.lowercase())
        if (millis == null || millis <= 0) errors += tr("purge.reason.bad_older", "value" to value)
        return millis?.takeIf { it > 0 }
    }
}
