package com.tracel.plugin.command.suggest

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.dialog.VANILLA_BLOCK_NAMES
import com.tracel.plugin.dialog.VANILLA_ITEM_NAMES
import com.tracel.plugin.command.args.ActionArgument
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Lookup suggestions: text and optional tooltip. */
data class LookupSuggestion(
    val text: String,
    val tooltip: String? = null,
)

// region Flags

private enum class FlagKind {
    SWITCH,
    SET,
    VALUE
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
    UNDO
}

private data class FlagToken(
    val aliases: List<String>,
    val tooltip: String,
    val kind: FlagKind,
    val group: FlagGroup,
    val exclusiveWith: Set<FlagGroup> = emptySet(),
    val rollbackOnly: Boolean = false,
)

// endregion

/** Lookup command suggestions. */
object LookupSuggest : SuggestionProvider<CommandSourceStack> {
    private val FLAGS: List<FlagToken> = listOf(
        FlagToken(
            listOf("undo"),
            "Take back the most recent rollback",
            FlagKind.SWITCH,
            FlagGroup.UNDO,
            rollbackOnly = true
        ),
        FlagToken(
            listOf("#preview"),
            "Preview changes without modifying world or inventory",
            FlagKind.SWITCH,
            FlagGroup.PREVIEW
        ),
        FlagToken(
            listOf("#blocks"),
            "Roll back structure and block changes only",
            FlagKind.SWITCH,
            FlagGroup.MODE_BLOCKS,
            exclusiveWith = setOf(FlagGroup.MODE_ITEMS)
        ),
        FlagToken(
            listOf("#items"),
            "Roll back material and inventory movements only",
            FlagKind.SWITCH,
            FlagGroup.MODE_ITEMS,
            exclusiveWith = setOf(FlagGroup.MODE_BLOCKS)
        ),
        FlagToken(
            listOf("#explosion"),
            "Crater and destroyed container contents",
            FlagKind.SWITCH,
            FlagGroup.EXPLOSION
        ),
        FlagToken(
            listOf("#strict"),
            "Preserve vanilla tick logic, do not force-rebuild",
            FlagKind.SWITCH,
            FlagGroup.STRICT
        ),
        FlagToken(
            listOf("#confirm"),
            "Confirm entity restores exceeding safety limit",
            FlagKind.SWITCH,
            FlagGroup.CONFIRM
        ),
        FlagToken(
            listOf("#wide"),
            "Horizontal scan only (ignore vertical height bounds)",
            FlagKind.SWITCH,
            FlagGroup.WIDE
        ),
        FlagToken(
            listOf("u:", "user:"),
            "Filter by player name (comma-separated)",
            FlagKind.SET,
            FlagGroup.USERS),
        FlagToken(
            listOf("-u:", "-user:"),
            "Exclude player name (comma-separated)",
            FlagKind.SET,
            FlagGroup.EXCLUDED_USERS
        ),
        FlagToken(
            listOf("t:", "time:", "after:", "before:"),
            "Time filter (e.g. 15m, 1h, 1d, today, yesterday)",
            FlagKind.VALUE,
            FlagGroup.TIME
        ),
        FlagToken(
            listOf("scope:"),
            "Radius cube (e.g. 10b, 5c) or the world name",
            FlagKind.VALUE,
            FlagGroup.SCOPE),
        FlagToken(
            listOf("w:"),
            "Filter by world name",
            FlagKind.VALUE,
            FlagGroup.WORLD),
        FlagToken(
            listOf("i:", "item:"),
            "Filter by item material",
            FlagKind.VALUE,
            FlagGroup.MATERIAL),
        FlagToken(
            listOf("b:", "block:"),
            "Filter by block material",
            FlagKind.VALUE,
            FlagGroup.MATERIAL),
        FlagToken(
            listOf("a:", "action:"),
            "Filter by action / cause (comma-separated)",
            FlagKind.SET,
            FlagGroup.ACTION
        ),
        FlagToken(
            listOf("l:", "lot:"),
            "Roll back specific lot ID",
            FlagKind.VALUE,
            FlagGroup.LOT),
    )

    private val FLAGS_BY_ALIAS: Map<String, FlagToken> =
        FLAGS.flatMap { def -> def.aliases.map { it.lowercase() to def } }.toMap()

    private val TIME_PRESETS = listOf(
        "15m" to "Past 15 minutes",
        "30m" to "Past 30 minutes",
        "1h" to "Past 1 hour",
        "2h" to "Past 2 hours",
        "6h" to "Past 6 hours",
        "12h" to "Past 12 hours",
        "1d" to "Past 24 hours",
        "3d" to "Past 3 days",
        "7d" to "Past 7 days",
        "today" to "Since midnight today",
        "yesterday" to "Since midnight yesterday",
    )

    private val SCOPE_PRESETS = listOf(
        "5b" to "5 blocks radius cube",
        "10b" to "10 blocks radius cube",
        "20b" to "20 blocks radius cube",
        "50b" to "50 blocks radius cube",
        "100b" to "100 blocks radius cube",
        "1c" to "1 chunk radius",
        "2c" to "2 chunks radius",
        "5c" to "5 chunks radius",
    )

    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remaining
        val lastSpace = remaining.lastIndexOf(' ')
        val currentToken = if (lastSpace >= 0) remaining.substring(lastSpace + 1) else remaining
        val tokenOffset = builder.start + if (lastSpace >= 0) lastSpace + 1 else 0
        val tokenBuilder = builder.createOffset(tokenOffset)

        val previousTokens = if (lastSpace >= 0) {
            remaining.substring(0, lastSpace).split(' ').filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        val suggestions = computeSuggestions(
            currentToken = currentToken,
            previousTokens = previousTokens,
            onlinePlayers = Bukkit.getOnlinePlayers().map { it.name },
            worldNames = Bukkit.getWorlds().map { it.name },
            actionNames = ActionArgument.NAMES,
            itemNames = VANILLA_ITEM_NAMES,
            blockNames = VANILLA_BLOCK_NAMES,
            includeUndo = isRollbackCommand(context.input),
        )

        for ((text, tooltip) in suggestions) {
            if (tooltip != null) tokenBuilder.suggest(text, LiteralMessage(tooltip))
            else tokenBuilder.suggest(text)
        }
        return tokenBuilder.buildFuture()
    }

    /** Compute suggestions for the given context. */
    fun computeSuggestions(
        currentToken: String,
        previousTokens: List<String>,
        onlinePlayers: List<String>,
        worldNames: List<String>,
        actionNames: List<String>,
        itemNames: List<String>,
        blockNames: List<String>,
        includeUndo: Boolean = false,
    ): List<LookupSuggestion> {
        val usedGroups = usedGroups(previousTokens)
        val matchingFlag = matchFlag(currentToken)

        if (matchingFlag != null && matchingFlag.kind != FlagKind.SWITCH) {
            return suggestForFlag(
                flag = matchingFlag,
                prefix = matchingPrefix(currentToken, matchingFlag)!!,
                currentToken = currentToken,
                onlinePlayers = onlinePlayers,
                worldNames = worldNames,
                actionNames = actionNames,
                itemNames = itemNames,
                blockNames = blockNames,
            )
        }

        return FLAGS
            .filter { isAvailable(it, usedGroups, includeUndo, previousTokens) }
            .flatMap { flag ->
                flag.aliases
                    .filter { it.startsWith(currentToken) }
                    .map { LookupSuggestion(it, flag.tooltip) }
            }
    }

    /** Suggest export files in the given directory. */
    fun suggestExportFiles(exportDirectory: Path, partial: String): List<String> = runCatching {
        if (!Files.isDirectory(exportDirectory)) return emptyList()
        Files.list(exportDirectory).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.endsWith(".tracel") && it.startsWith(partial) }
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun usedGroups(tokens: List<String>): Set<FlagGroup> =
        tokens.mapNotNull { matchFlag(it)?.group }.toSet()

    private fun isAvailable(
        flag: FlagToken,
        used: Set<FlagGroup>,
        includeUndo: Boolean,
        previousTokens: List<String>,
    ): Boolean {
        if (flag.rollbackOnly && !includeUndo) return false
        if (flag.group == FlagGroup.UNDO && previousTokens.isNotEmpty()) return false
        if (flag.group in used) return false
        if (flag.exclusiveWith.any { it in used }) return false
        return true
    }

    private fun matchFlag(token: String): FlagToken? {
        val lower = token.lowercase()
        FLAGS_BY_ALIAS[lower]?.let { return it }
        return FLAGS.firstOrNull { def ->
            def.aliases.any { alias -> lower.startsWith(alias.lowercase()) }
        }
    }

    private fun matchingPrefix(token: String, flag: FlagToken): String? {
        val lower = token.lowercase()
        return flag.aliases.firstOrNull { lower.startsWith(it.lowercase()) }
    }

    private fun suggestForFlag(
        flag: FlagToken,
        prefix: String,
        currentToken: String,
        onlinePlayers: List<String>,
        worldNames: List<String>,
        actionNames: List<String>,
        itemNames: List<String>,
        blockNames: List<String>,
    ): List<LookupSuggestion> {
        val raw = currentToken.removePrefix(prefix)
        return when (flag.group) {
            FlagGroup.USERS, FlagGroup.EXCLUDED_USERS ->
                suggestCsv(prefix, raw, onlinePlayers) { "Player: $it" }

            FlagGroup.ACTION ->
                suggestCsv(prefix, raw, actionNames) { "Action: $it" }

            FlagGroup.TIME ->
                suggestPresets(prefix, raw, TIME_PRESETS)

            FlagGroup.SCOPE ->
                suggestPresets(prefix, raw, SCOPE_PRESETS) +
                        rank(worldNames, raw).map { LookupSuggestion("$prefix$it", "Scope to world: $it") }

            FlagGroup.WORLD ->
                rank(worldNames, raw).map { LookupSuggestion("$prefix$it", "World: $it") }

            FlagGroup.MATERIAL -> when {
                prefix.startsWith("b") || prefix.startsWith("block") ->
                    rank(blockNames, raw, limit = 30).map { LookupSuggestion("$prefix$it", "Block: $it") }

                else ->
                    rank(itemNames, raw, limit = 30).map { LookupSuggestion("$prefix$it", "Item: $it") }
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
    ): List<LookupSuggestion> {
        val lastComma = rawValue.lastIndexOf(',')
        val before = if (lastComma >= 0) rawValue.substring(0, lastComma) else ""
        val typing = if (lastComma >= 0) rawValue.substring(lastComma + 1) else rawValue
        val selected = if (before.isEmpty()) emptySet() else before.split(',').toSet()
        val rebuiltPrefix = if (lastComma >= 0) "$prefix$before," else prefix
        return rank(universe.filter { it !in selected }, typing)
            .map { LookupSuggestion("$rebuiltPrefix$it", tooltip(it)) }
    }

    private fun suggestPresets(
        prefix: String,
        rawValue: String,
        presets: List<Pair<String, String>>,
    ): List<LookupSuggestion> {
        val needle = rawValue.lowercase()
        return presets
            .filter { it.first.startsWith(needle) }
            .map { LookupSuggestion("$prefix${it.first}", it.second) }
    }

    private fun rank(names: List<String>, raw: String, limit: Int = Int.MAX_VALUE): List<String> {
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

    private fun isRollbackCommand(input: String): Boolean {
        val first = input.trimStart().substringAfter('/').substringBefore(' ')
        return first.equals("rollback", ignoreCase = true) ||
                first.endsWith(":rollback", ignoreCase = true)
    }
}
