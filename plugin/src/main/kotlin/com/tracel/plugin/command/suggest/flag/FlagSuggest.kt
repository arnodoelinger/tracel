package com.tracel.plugin.command.suggest.flag

import com.tracel.plugin.command.args.scope.ScopeLimits
import com.tracel.plugin.command.suggest.SuggestLists
import com.tracel.plugin.command.suggest.Suggestion
import com.tracel.plugin.command.suggest.quantity.presetsOf
import com.tracel.plugin.command.suggest.quantity.suggestQuantity
import com.tracel.plugin.command.suggest.rank
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component

internal object FlagSuggest {
    fun complete(
        profile: FlagProfile,
        current: String,
        previous: List<String>,
        lists: SuggestLists,
    ): List<Suggestion> {
        val used = usedGroups(previous)
        val typed = current.lowercase()
        val matched = matchFlag(typed)
        if (matched != null && matched.flag.kind != FlagKind.SWITCH && profile.accepts(matched.flag) && matched.flag.group !in used) {
            return suggestValue(matched.flag, matched.alias, current, lists)
        }

        val flags = FLAGS
            .filter { profile.accepts(it) && available(it, used, typed) }
            .flatMap { flag ->
                flag.aliases
                    .filter { alias ->
                        if (typed.isEmpty()) alias == flag.aliases.first()
                        else alias.startsWith(typed)
                    }
                    .map { Suggestion(it, tr("suggest.flag.${flag.tooltip}")) }
            }
        return flags + presets(typed, if (profile == FlagProfile.PRESET) lists.copy(presets = emptyList()) else lists)
    }

    private fun presets(typed: String, lists: SuggestLists): List<Suggestion> {
        if (typed.isEmpty()) return lists.presets.take(5).map { Suggestion("@${it.first}", Component.text(it.second)) }
        if (typed.startsWith("@")) {
            val needle = typed.substring(1).lowercase()
            return lists.presets.filter { it.first.startsWith(needle) }
                .map { Suggestion("@${it.first}", Component.text(it.second)) }
        }
        return emptyList()
    }

    private fun available(flag: FlagToken, used: Set<FlagGroup>, typed: String): Boolean {
        if (flag.group in used) return false
        if (flag.exclusiveWith.any { it in used }) return false
        if (flag.quiet && typed.isEmpty()) return false
        if (flag.quiet && flag.aliases.none { it.startsWith(typed) }) return false
        return true
    }

    private fun usedGroups(tokens: List<String>): Set<FlagGroup> =
        tokens.mapNotNull { matchFlag(it.lowercase())?.flag?.group }.toSet()

    private fun suggestValue(
        flag: FlagToken,
        alias: String,
        current: String,
        lists: SuggestLists,
    ): List<Suggestion> {
        val raw = current.substring(alias.length)
        return when (flag.group) {
            FlagGroup.USERS ->
                suggestCsv(alias, raw, lists.onlinePlayers) { tr("suggest.player", "name" to it) }

            FlagGroup.ACTION ->
                suggestCsv(alias, raw, lists.actionNames, ::actionTip)

            FlagGroup.TIME -> {
                val window = alias == "t:" || alias == "time:"
                val units = if (window) WINDOW_UNITS else POINT_UNITS
                suggestQuantity(
                    prefix = alias,
                    raw = raw,
                    units = units,
                    presets = presetsOf(TIME_PRESETS, units) + if (window) NAMED_DAYS else emptyList(),
                    compound = true,
                    describeWhole = { text ->
                        spanOf(text)?.let {
                            tr(
                                if (window) "suggest.past" else "suggest.ago",
                                "span" to it
                            )
                        }
                    },
                )
            }

            FlagGroup.SCOPE -> suggestQuantity(
                prefix = alias,
                raw = raw,
                units = SCOPE_UNITS,
                presets = presetsOf(SCOPE_PRESETS, SCOPE_UNITS),
                words = WHERE_WORDS + lists.worldNames.map { it to tr("suggest.scope_world", "name" to it) },
                allowed = { unit, amount ->
                    amount <= if (unit.suffix == "b") ScopeLimits.MAX_BLOCK_RADIUS else ScopeLimits.MAX_CHUNK_RADIUS
                },
            )

            FlagGroup.WORLD ->
                rank(lists.worldNames, raw).map { Suggestion("$alias$it", tr("suggest.world", "name" to it)) }

            FlagGroup.MATERIAL -> {
                val blocks = alias == "b:" || alias == "block:"
                val names = if (blocks) lists.blockNames else lists.itemNames
                val kind = if (blocks) "suggest.block" else "suggest.item"
                rank(names, raw, limit = 30).map { Suggestion("$alias$it", tr(kind, "name" to it)) }
            }

            else -> emptyList()
        }
    }

    private fun suggestCsv(
        prefix: String,
        rawValue: String,
        universe: List<String>,
        tooltip: (String) -> Component,
    ): List<Suggestion> {
        val lastComma = rawValue.lastIndexOf(',')
        val before = if (lastComma >= 0) rawValue.substring(0, lastComma) else ""
        val typing = if (lastComma >= 0) rawValue.substring(lastComma + 1) else rawValue
        val selected = if (before.isEmpty()) emptySet() else before.split(',').toSet()
        val rebuilt = if (lastComma >= 0) "$prefix$before," else prefix
        return rank(universe.filter { it !in selected }, typing)
            .map { Suggestion("$rebuilt$it", tooltip(it)) }
    }
}

private fun matchFlag(token: String): MatchedFlag? {
    var best: MatchedFlag? = null
    for (flag in FLAGS) {
        for (alias in flag.aliases) {
            if (!token.startsWith(alias)) continue
            val current = best
            if (current == null || alias.length > current.alias.length) {
                best = MatchedFlag(flag, alias)
            }
        }
    }
    return best
}
