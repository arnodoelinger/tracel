package com.tracel.plugin.command.suggest

import com.tracel.plugin.command.args.ScopeLimits
import com.tracel.plugin.command.args.TimeArgument
import com.tracel.plugin.command.suggest.support.QuantityUnit
import com.tracel.plugin.command.suggest.support.ago
import com.tracel.plugin.command.suggest.support.around
import com.tracel.plugin.command.suggest.support.past
import com.tracel.plugin.command.suggest.support.presetsOf
import com.tracel.plugin.command.suggest.support.suggestQuantity


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
    TRACE,
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
        tooltip = "Preview changes without modifying the world or inventory",
        kind = FlagKind.SWITCH,
        group = FlagGroup.PREVIEW,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#blocks"),
        tooltip = "Structure and block changes only",
        kind = FlagKind.SWITCH,
        group = FlagGroup.MODE_BLOCKS,
        profiles = BOTH,
        exclusiveWith = setOf(FlagGroup.MODE_ITEMS)
    ),
    FlagToken(
        aliases = listOf("#items"),
        tooltip = "Material and inventory movements only",
        kind = FlagKind.SWITCH,
        group = FlagGroup.MODE_ITEMS,
        profiles = BOTH,
        exclusiveWith = setOf(FlagGroup.MODE_BLOCKS)
    ),
    FlagToken(
        aliases = listOf("#explosion"),
        tooltip = "Crater and the container contents it destroyed",
        kind = FlagKind.SWITCH,
        group = FlagGroup.EXPLOSION,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("#strict"),
        tooltip = "Preserve vanilla tick logic, do not force-rebuild",
        kind = FlagKind.SWITCH,
        group = FlagGroup.STRICT,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#confirm"),
        tooltip = "Confirm entity restores that exceed the safety limit",
        kind = FlagKind.SWITCH,
        group = FlagGroup.CONFIRM,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#wide"),
        tooltip = "Horizontal scan only, ignore vertical bounds",
        kind = FlagKind.SWITCH,
        group = FlagGroup.WIDE,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("#trace"),
        tooltip = "Print how long each rollback phase took",
        kind = FlagKind.SWITCH,
        group = FlagGroup.TRACE,
        profiles = ROLLBACK_ONLY,
        quiet = true
    ),
    FlagToken(
        aliases = listOf("u:", "user:"),
        tooltip = "Player name, comma-separated",
        kind = FlagKind.SET,
        group = FlagGroup.USERS,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("t:", "time:", "after:", "before:"),
        tooltip = "When it happened",
        kind = FlagKind.VALUE,
        group = FlagGroup.TIME,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("s:", "scope:"),
        tooltip = "Radius around you: 20b, 2c, block or chunk",
        kind = FlagKind.VALUE,
        group = FlagGroup.SCOPE,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("w:"),
        tooltip = "World name",
        kind = FlagKind.VALUE,
        group = FlagGroup.WORLD,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("i:", "item:"),
        tooltip = "Item material",
        kind = FlagKind.VALUE,
        group = FlagGroup.MATERIAL,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("b:", "block:"),
        tooltip = "Block material",
        kind = FlagKind.VALUE,
        group = FlagGroup.MATERIAL,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("a:", "action:"),
        tooltip = "Action or cause, comma-separated",
        kind = FlagKind.SET,
        group = FlagGroup.ACTION,
        profiles = BOTH
    )
)

private val TIME_PRESETS = listOf("10s", "30s", "1m", "5m", "10m", "30m", "1h", "3h", "6h", "12h", "1d", "3d", "7d")

private val NAMED_DAYS = listOf(
    "today" to "Since midnight today",
    "yesterday" to "Since midnight yesterday",
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

private fun spanOf(text: String): String? {
    var left = TimeArgument.parseDuration(text) ?: return null
    val parts = ArrayList<String>()
    for ((millis, noun) in SPAN_PARTS) {
        val n = left / millis
        left %= millis
        if (n > 0) parts += if (n == 1L) "1 $noun" else "$n ${noun}s"
    }
    return parts.joinToString(" ").ifEmpty { null }
}

private val ACTION_TIPS = mapOf(
    "block" to "Blocks placed, broken, or changed",
    "+block" to "Blocks placed",
    "place" to "Blocks placed",
    "-block" to "Blocks broken",
    "break" to "Blocks broken",
    "sign" to "Sign edits",
    "entity" to "Entities spawned, removed, or changed",
    "+entity" to "Entities spawned",
    "-entity" to "Entities removed",
    "kill" to "Entities removed",
    "container" to "Container and inventory moves",
    "item" to "Container and inventory moves",
    "inventory" to "Container and inventory moves",
    "craft" to "Crafting",
    "explosion" to "Explosions",
)

private data class MatchedFlag(val flag: FlagToken, val alias: String)

internal enum class FlagProfile {
    LOOKUP,
    ROLLBACK,
}

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
        if (matched != null && matched.flag.kind != FlagKind.SWITCH && profile in matched.flag.profiles && matched.flag.group !in used) {
            return suggestValue(matched.flag, matched.alias, current, lists)
        }

        val flags = FLAGS
            .filter { profile in it.profiles && available(it, used, typed) }
            .flatMap { flag ->
                flag.aliases
                    .filter { alias ->
                        if (typed.isEmpty()) alias == flag.aliases.first()
                        else alias.startsWith(typed)
                    }
                    .map { Suggestion(it, flag.tooltip) }
            }
        return flags + bare(typed, used, lists)
    }

    private fun bare(typed: String, used: Set<FlagGroup>, lists: SuggestLists): List<Suggestion> {
        if (typed.isEmpty()) return lists.presets.take(5).map { Suggestion("@${it.first}", it.second) }
        if (typed.startsWith("@")) {
            val needle = typed.substring(1).lowercase()
            return lists.presets.filter { it.first.startsWith(needle) }.map { Suggestion("@${it.first}", it.second) }
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
        for (name in rank(lists.onlinePlayers, needle, limit = 10)) out += Suggestion(name, "Player $name")
        if (FlagGroup.SCOPE !in used) {
            for ((word, tip) in listOf("block" to "The block you are standing on", "chunk" to "The chunk you are standing in")) {
                if (word.startsWith(needle.lowercase())) out += Suggestion(word, tip)
            }
        }
        if (FlagGroup.TIME !in used) {
            for ((word, tip) in NAMED_DAYS) if (word.startsWith(needle.lowercase())) out += Suggestion(word, tip)
        }
        if (FlagGroup.WORLD !in used) {
            for (name in rank(lists.worldNames, needle, limit = 5)) out += Suggestion(name, "World $name")
        }
        if (FlagGroup.ACTION !in used) {
            for (name in rank(lists.actionNames, needle, limit = 8)) out += Suggestion(name, ACTION_TIPS[name] ?: "Action $name")
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
                suggestCsv(alias, raw, lists.onlinePlayers) { "Player $it" }

            FlagGroup.ACTION ->
                suggestCsv(alias, raw, lists.actionNames) { ACTION_TIPS[it] ?: "Action $it" }

            FlagGroup.TIME -> {
                val window = alias == "t:" || alias == "time:"
                val units = if (window) WINDOW_UNITS else POINT_UNITS
                suggestQuantity(
                    prefix = alias,
                    raw = raw,
                    units = units,
                    presets = presetsOf(TIME_PRESETS, units) + if (window) NAMED_DAYS else emptyList(),
                    compound = true,
                    describeWhole = { text -> spanOf(text)?.let { if (window) "Past $it" else "$it ago" } },
                )
            }

            FlagGroup.SCOPE -> suggestQuantity(
                prefix = alias,
                raw = raw,
                units = SCOPE_UNITS,
                presets = presetsOf(SCOPE_PRESETS, SCOPE_UNITS),
                words = listOf(
                    "block" to "The block you are standing on",
                    "chunk" to "The chunk you are standing in",
                ) +
                        lists.worldNames.map { it to "Scope to world $it" },
                allowed = { unit, amount ->
                    amount <= if (unit.suffix == "b") ScopeLimits.MAX_BLOCK_RADIUS else ScopeLimits.MAX_CHUNK_RADIUS
                },
            )

            FlagGroup.WORLD ->
                rank(lists.worldNames, raw).map { Suggestion("$alias$it", "World $it") }

            FlagGroup.MATERIAL -> {
                val blocks = alias == "b:" || alias == "block:"
                val names = if (blocks) lists.blockNames else lists.itemNames
                val kind = if (blocks) "Block" else "Item"
                rank(names, raw, limit = 30).map { Suggestion("$alias$it", "$kind $it") }
            }

            else -> emptyList()
        }
    }

    private fun suggestCsv(
        prefix: String,
        rawValue: String,
        universe: List<String>,
        tooltip: (String) -> String,
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
