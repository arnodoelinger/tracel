package com.tracel.plugin.command.suggest

import com.tracel.plugin.command.suggest.support.QuantityUnit
import com.tracel.plugin.command.suggest.support.ago
import com.tracel.plugin.command.suggest.support.around
import com.tracel.plugin.command.suggest.support.suggestQuantity


private enum class FlagKind {
    SWITCH,
    SET,
    VALUE,
}

private enum class FlagGroup {
    USERS,
    EXCLUDED_USERS,
    TIME,
    SCOPE,
    WORLD,
    MATERIAL,
    ACTION,
    LOT,
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
        aliases = listOf("-u:", "-user:"),
        tooltip = "Player name to exclude, comma-separated",
        kind = FlagKind.SET,
        group = FlagGroup.EXCLUDED_USERS,
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
        aliases = listOf("scope:"),
        tooltip = "Radius around you, or a world name",
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
    ),
    FlagToken(
        aliases = listOf("l:", "lot:"),
        tooltip = "One lot id",
        kind = FlagKind.VALUE,
        group = FlagGroup.LOT,
        profiles = ROLLBACK_ONLY
    )
)

private val DURATION_PRESETS = listOf(
    "15m" to "Past 15 minutes",
    "30m" to "Past 30 minutes",
    "1h" to "Past 1 hour",
    "2h" to "Past 2 hours",
    "6h" to "Past 6 hours",
    "12h" to "Past 12 hours",
    "1d" to "Past 24 hours",
    "3d" to "Past 3 days",
    "7d" to "Past 7 days",
)

private val NAMED_DAYS = listOf(
    "today" to "Since midnight today",
    "yesterday" to "Since midnight yesterday",
)

private val SCOPE_PRESETS = listOf(
    "5b" to "5 blocks around you",
    "10b" to "10 blocks around you",
    "20b" to "20 blocks around you",
    "50b" to "50 blocks around you",
    "100b" to "100 blocks around you",
    "1c" to "1 chunk around you",
    "2c" to "2 chunks around you",
    "5c" to "5 chunks around you",
)

private val TIME_UNITS = listOf(
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

        return FLAGS
            .filter { profile in it.profiles && available(it, used, typed) }
            .flatMap { flag ->
                flag.aliases
                    .filter { alias ->
                        if (typed.isEmpty()) alias == flag.aliases.first()
                        else alias.startsWith(typed)
                    }
                    .map { Suggestion(it, flag.tooltip) }
            }
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
            FlagGroup.USERS, FlagGroup.EXCLUDED_USERS ->
                suggestCsv(alias, raw, lists.onlinePlayers) { "Player $it" }

            FlagGroup.ACTION ->
                suggestCsv(alias, raw, lists.actionNames) { ACTION_TIPS[it] ?: "Action $it" }

            FlagGroup.TIME -> suggestQuantity(
                prefix = alias,
                raw = raw,
                units = TIME_UNITS,
                presets = DURATION_PRESETS + if (alias == "t:" || alias == "time:") NAMED_DAYS else emptyList(),
                compound = true,
            )

            FlagGroup.SCOPE -> suggestQuantity(
                prefix = alias,
                raw = raw,
                units = SCOPE_UNITS,
                presets = SCOPE_PRESETS,
                words = listOf("chunk" to "The chunk you are standing in") +
                        lists.worldNames.map { it to "Scope to world $it" },
            )

            FlagGroup.WORLD ->
                rank(lists.worldNames, raw).map { Suggestion("$alias$it", "World $it") }

            FlagGroup.MATERIAL -> {
                val blocks = alias == "b:" || alias == "block:"
                val names = if (blocks) lists.blockNames else lists.itemNames
                val kind = if (blocks) "Block" else "Item"
                rank(names, raw, limit = 30).map { Suggestion("$alias$it", "$kind $it") }
            }

            FlagGroup.LOT -> emptyList()
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
