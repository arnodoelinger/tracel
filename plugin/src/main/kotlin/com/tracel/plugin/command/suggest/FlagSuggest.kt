package com.tracel.plugin.command.suggest

import com.tracel.plugin.command.args.ScopeLimits
import com.tracel.plugin.command.args.TimeArgument
import com.tracel.plugin.command.suggest.support.QuantityUnit
import com.tracel.plugin.command.suggest.support.ago
import com.tracel.plugin.command.suggest.support.around
import com.tracel.plugin.command.suggest.support.past
import com.tracel.plugin.command.suggest.support.presetsOf
import com.tracel.plugin.command.suggest.support.span
import com.tracel.plugin.command.suggest.support.suggestQuantity
import com.tracel.plugin.i18n.joined
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component

private enum class FlagKind {
    SWITCH,
    SET,
    VALUE,
}

private enum class FlagGroup {
    USERS,
    TIME,
    SCOPE,
    WORLD,
    MATERIAL,
    ACTION,
    PREVIEW,
    MODE_BLOCKS,
    MODE_ITEMS,
    EXPLOSION,
    STRICT,
    CONFIRM,
    WIDE,
    NATURAL,
    EACH,
}

private data class FlagToken(
    val aliases: List<String>,
    val tooltip: String,
    val kind: FlagKind,
    val group: FlagGroup,
    val profiles: Set<FlagProfile>,
    val exclusiveWith: Set<FlagGroup> = emptySet(),
    val quiet: Boolean = false, // Hidden until the typed token actually reaches for it
)

private val BOTH = setOf(FlagProfile.LOOKUP, FlagProfile.ROLLBACK)
private val ROLLBACK_ONLY = setOf(FlagProfile.ROLLBACK)

private val FLAGS: List<FlagToken> = listOf(
    FlagToken(
        aliases = listOf("#preview"),
        tooltip = "preview",
        kind = FlagKind.SWITCH,
        group = FlagGroup.PREVIEW,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#blocks"),
        tooltip = "blocks",
        kind = FlagKind.SWITCH,
        group = FlagGroup.MODE_BLOCKS,
        profiles = BOTH,
        exclusiveWith = setOf(FlagGroup.MODE_ITEMS)
    ),
    FlagToken(
        aliases = listOf("#items"),
        tooltip = "items",
        kind = FlagKind.SWITCH,
        group = FlagGroup.MODE_ITEMS,
        profiles = BOTH,
        exclusiveWith = setOf(FlagGroup.MODE_BLOCKS)
    ),
    FlagToken(
        aliases = listOf("#explosion"),
        tooltip = "explosion",
        kind = FlagKind.SWITCH,
        group = FlagGroup.EXPLOSION,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("#strict"),
        tooltip = "strict",
        kind = FlagKind.SWITCH,
        group = FlagGroup.STRICT,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#confirm"),
        tooltip = "confirm",
        kind = FlagKind.SWITCH,
        group = FlagGroup.CONFIRM,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#each"),
        tooltip = "each",
        kind = FlagKind.SWITCH,
        group = FlagGroup.EACH,
        profiles = setOf(FlagProfile.LOOKUP)
    ),
    FlagToken(
        aliases = listOf("#world"),
        tooltip = "natural",
        kind = FlagKind.SWITCH,
        group = FlagGroup.NATURAL,
        profiles = setOf(FlagProfile.LOOKUP)
    ),
    FlagToken(
        aliases = listOf("#wide"),
        tooltip = "wide",
        kind = FlagKind.SWITCH,
        group = FlagGroup.WIDE,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("u:", "user:"),
        tooltip = "user",
        kind = FlagKind.SET,
        group = FlagGroup.USERS,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("t:", "time:", "after:", "before:"),
        tooltip = "time",
        kind = FlagKind.VALUE,
        group = FlagGroup.TIME,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("s:", "scope:"),
        tooltip = "scope",
        kind = FlagKind.VALUE,
        group = FlagGroup.SCOPE,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("w:"),
        tooltip = "world",
        kind = FlagKind.VALUE,
        group = FlagGroup.WORLD,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("i:", "item:"),
        tooltip = "item",
        kind = FlagKind.VALUE,
        group = FlagGroup.MATERIAL,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("b:", "block:"),
        tooltip = "block",
        kind = FlagKind.VALUE,
        group = FlagGroup.MATERIAL,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("a:", "action:"),
        tooltip = "action",
        kind = FlagKind.SET,
        group = FlagGroup.ACTION,
        profiles = BOTH
    )
)

private val TIME_PRESETS = listOf("10s", "30s", "1m", "5m", "10m", "30m", "1h", "3h", "6h", "12h", "1d", "3d", "7d")

private val NAMED_DAYS = listOf(
    "today" to tr("suggest.today"),
    "yesterday" to tr("suggest.yesterday"),
)

private val SCOPE_PRESETS = listOf("4b", "8b", "16b", "32b", "64b", "128b", "1c", "2c", "4c", "8c")

private val WINDOW_UNITS = listOf(
    QuantityUnit("s") { past(it, "second") },
    QuantityUnit("m") { past(it, "minute") },
    QuantityUnit("h") { past(it, "hour") },
    QuantityUnit("d") { past(it, "day") },
    QuantityUnit("w") { past(it, "week") },
)

private val POINT_UNITS = listOf(
    QuantityUnit("s") { ago(it, "second") },
    QuantityUnit("m") { ago(it, "minute") },
    QuantityUnit("h") { ago(it, "hour") },
    QuantityUnit("d") { ago(it, "day") },
    QuantityUnit("w") { ago(it, "week") },
)

private val SCOPE_UNITS = listOf(
    QuantityUnit("b") { around(it, "block") },
    QuantityUnit("c") { around(it, "chunk") },
)

private val SPAN_PARTS = listOf(
    604_800_000L to "week",
    86_400_000L to "day",
    3_600_000L to "hour",
    60_000L to "minute",
    1_000L to "second",
)

private fun spanOf(text: String): Component? {
    var left = TimeArgument.parseDuration(text) ?: return null
    val parts = ArrayList<Component>()
    for ((millis, noun) in SPAN_PARTS) {
        val n = left / millis
        left %= millis
        if (n > 0) parts += span(n, noun)
    }
    return if (parts.isEmpty()) null else parts.joined(" ")
}

private val ACTION_TIPS = mapOf(
    "block" to "block",
    "+block" to "place",
    "place" to "place",
    "-block" to "break",
    "break" to "break",
    "sign" to "sign",
    "entity" to "entity",
    "+entity" to "spawn",
    "-entity" to "kill",
    "kill" to "kill",
    "container" to "container",
    "item" to "container",
    "inventory" to "container",
    "craft" to "craft",
    "explosion" to "explosion",
    "click" to "click",
    "chat" to "chat",
    "command" to "command",
    "session" to "session",
    "+session" to "join",
    "join" to "join",
    "-session" to "quit",
    "quit" to "quit",
    "death" to "death",
)

private fun actionTip(name: String): Component =
    ACTION_TIPS[name]?.let { tr("suggest.action.$it") } ?: Component.text(name)

private val WHERE_WORDS = listOf(
    "block" to tr("suggest.scope_block"),
    "chunk" to tr("suggest.scope_chunk"),
)

private data class MatchedFlag(val flag: FlagToken, val alias: String)

internal enum class FlagProfile {
    LOOKUP,
    ROLLBACK,
    PRESET,
}

private fun FlagProfile.accepts(flag: FlagToken) = if (this == FlagProfile.PRESET) flag.profiles.isNotEmpty() else this in flag.profiles

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
        return flags + bare(typed, used, if (profile == FlagProfile.PRESET) lists.copy(presets = emptyList()) else lists)
    }

    private fun bare(typed: String, used: Set<FlagGroup>, lists: SuggestLists): List<Suggestion> {
        if (typed.isEmpty()) return lists.presets.take(5).map { Suggestion("@${it.first}", Component.text(it.second)) }
        if (typed.startsWith("@")) {
            val needle = typed.substring(1).lowercase()
            return lists.presets.filter { it.first.startsWith(needle) }.map { Suggestion("@${it.first}", Component.text(it.second)) }
        }
        if (typed.startsWith("#")) return emptyList()
        val out = ArrayList<Suggestion>()
        if (typed.first().isDigit()) {
            if (FlagGroup.TIME !in used) {
                out += suggestQuantity(
                    prefix = "",
                    raw = typed,
                    units = WINDOW_UNITS,
                    presets = presetsOf(TIME_PRESETS, WINDOW_UNITS),
                    compound = true,
                )
            }
            if (FlagGroup.SCOPE !in used) {
                out += suggestQuantity(
                    prefix = "",
                    raw = typed,
                    units = SCOPE_UNITS,
                    presets = presetsOf(SCOPE_PRESETS, SCOPE_UNITS),
                    allowed = { unit, amount ->
                        amount <= if (unit.suffix == "b") ScopeLimits.MAX_BLOCK_RADIUS else ScopeLimits.MAX_CHUNK_RADIUS
                    },
                )
            }
            return out
        }
        val needle = typed
        for (name in rank(lists.onlinePlayers, needle, limit = 10)) out += Suggestion(name, tr("suggest.player", "name" to name))
        if (FlagGroup.SCOPE !in used) {
            for ((word, tip) in WHERE_WORDS) {
                if (word.startsWith(needle.lowercase())) out += Suggestion(word, tip)
            }
        }
        if (FlagGroup.TIME !in used) {
            for ((word, tip) in NAMED_DAYS) if (word.startsWith(needle.lowercase())) out += Suggestion(word, tip)
        }
        if (FlagGroup.WORLD !in used) {
            for (name in rank(lists.worldNames, needle, limit = 5)) out += Suggestion(name, tr("suggest.world", "name" to name))
        }
        if (FlagGroup.ACTION !in used) {
            for (name in rank(lists.actionNames, needle, limit = 8)) out += Suggestion(name, actionTip(name))
        }
        return out
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
                    describeWhole = { text -> spanOf(text)?.let { tr(if (window) "suggest.past" else "suggest.ago", "span" to it) } },
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
