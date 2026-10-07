package com.tracel.plugin.command.args.lookup

import com.tracel.plugin.command.args.scope.ScopeArgument
import com.tracel.plugin.command.args.scope.withScope
import com.tracel.plugin.command.args.time.TimeArgument
import com.tracel.plugin.command.args.time.TimeExpr
import com.tracel.plugin.command.suggest.LookupSuggest
import com.tracel.plugin.command.suggest.SuggestLists
import com.tracel.plugin.i18n.tr

private fun ParsedLookupArgs.within(window: TimeExpr): ParsedLookupArgs =
    copy(since = later(since, window.since), until = earlier(until, window.until))

private fun later(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)

private fun earlier(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)

private val LOOKUP_ARGUMENTS: List<LookupArgument> = listOf(
    LookupArgument.Flag("#preview") { it.copy(preview = true) },
    LookupArgument.Flag("#blocks") { it.copy(structureOnly = true) },
    LookupArgument.Flag("#items") { it.copy(materialOnly = true) },
    LookupArgument.Flag("#explosion") { it.copy(actions = it.actions + "explosion") },
    LookupArgument.Flag("#strict") { it.copy(strict = true) },
    LookupArgument.Flag("#confirm") { it.copy(confirmed = true) },
    LookupArgument.Flag("#each") { it.copy(each = true) },
    LookupArgument.Flag("#world") { it.copy(natural = true) },
    LookupArgument.Flag("#all") { it.copy(natural = true, all = true) },
    LookupArgument.Flag("#wide") { it.copy(horizontalOnly = true) },

    LookupArgument.Multi("user:", { it.users }, { r, v -> r.copy(users = v) }, { it.onlinePlayerNames }),
    LookupArgument.Value("item:", { r, v -> r.copy(item = v) }, { it.itemNames }),
    LookupArgument.Value("block:", { r, v -> r.copy(item = v) }, { it.blockNames }),
    LookupArgument.Multi("action:", { it.actions }, { r, v -> r.copy(actions = v) }, { it.causeNames }),
    LookupArgument.Parsed(
        "world:",
        { v, _ -> v.takeIf(String::isNotBlank) },
        { r, v -> r.copy(world = v) },
        { it.worldNames }),
    LookupArgument.Parsed(
        "scope:",
        { v, _ -> ScopeArgument.parse(v) },
        { r, v -> r.withScope(v) },
        { ScopeArgument.suggestions(it.worldNames) }),
    LookupArgument.Parsed(
        "before:",
        { v, now -> TimeArgument.parseDuration(v)?.let { now - it } },
        { r, v -> r.copy(until = earlier(r.until, v)) },
        { TimeArgument.durationSuggestions() }),
    LookupArgument.Parsed(
        "after:",
        { v, now -> TimeArgument.parseDuration(v)?.let { now - it } },
        { r, v -> r.copy(since = later(r.since, v)) },
        { TimeArgument.durationSuggestions() }),
    LookupArgument.Parsed(
        "time:",
        { v, now -> TimeArgument.parseExpr(v, now) },
        { r, v -> r.within(v) },
        { TimeArgument.timeSuggestions() }),

    LookupArgument.Parsed("page:", { v, _ -> v.toIntOrNull()?.takeIf { it >= 1 } }, { r, v -> r.copy(page = v) }),

    // Aliases
    LookupArgument.Parsed("p:", { v, _ -> v.toIntOrNull()?.takeIf { it >= 1 } }, { r, v -> r.copy(page = v) }),
    LookupArgument.Multi("u:", { it.users }, { r, v -> r.copy(users = v) }, { it.onlinePlayerNames }),
    LookupArgument.Value("i:", { r, v -> r.copy(item = v) }, { it.itemNames }),
    LookupArgument.Value("b:", { r, v -> r.copy(item = v) }, { it.blockNames }),
    LookupArgument.Multi("a:", { it.actions }, { r, v -> r.copy(actions = v) }, { it.causeNames }),
    LookupArgument.Parsed(
        "s:",
        { v, _ -> ScopeArgument.parse(v) },
        { r, v -> r.withScope(v) },
        { ScopeArgument.suggestions(it.worldNames) }),
    LookupArgument.Parsed(
        "w:",
        { v, _ -> v.takeIf(String::isNotBlank) },
        { r, v -> r.copy(world = v) },
        { it.worldNames }),
    LookupArgument.Parsed(
        "t:",
        { v, now -> TimeArgument.parseExpr(v, now) },
        { r, v -> r.within(v) },
        { TimeArgument.timeSuggestions() }),
)

/** Parses flag tokens. Every value has its flag: `t:10m`, `s:20b`, `u:Name`; a bare word is an error. */
internal fun parseLookupArgs(args: List<String>, nowMillis: Long): ParsedLookupArgs =
    args.fold(ParsedLookupArgs()) { result, token ->
        LOOKUP_ARGUMENTS.firstOrNull { it.matches(token) }?.apply(result, token, nowMillis)
            ?: result.copy(errors = result.errors + tr("common.invalid", "token" to token))
    }

/** What is set here stays; what is empty here comes from [preset]. Errors of both are kept. */
internal fun ParsedLookupArgs.filledFrom(preset: ParsedLookupArgs): ParsedLookupArgs = copy(
    users = users.ifEmpty { preset.users },
    item = item ?: preset.item,
    actions = actions.ifEmpty { preset.actions },
    since = if (since == null && until == null) preset.since else since,
    until = if (since == null && until == null) preset.until else until,
    scope = scope ?: preset.scope,
    world = world ?: preset.world,
    horizontalOnly = horizontalOnly || preset.horizontalOnly,
    natural = natural || preset.natural,
    all = all || preset.all,
    each = each || preset.each,
    preview = preview || preset.preview,
    structureOnly = structureOnly || preset.structureOnly,
    materialOnly = materialOnly || preset.materialOnly,
    strict = strict || preset.strict,
    confirmed = confirmed || preset.confirmed,
    errors = errors + preset.errors,
)

/** Suggest lookup token. */
fun suggestLookupToken(
    partial: String,
    onlinePlayerNames: List<String>,
    worldNames: List<String>,
    causeNames: List<String>,
    itemNames: List<String> = emptyList(),
    blockNames: List<String> = emptyList(),
): List<String> = LookupSuggest.suggest(
    line = partial,
    lists = SuggestLists(
        onlinePlayers = onlinePlayerNames,
        worldNames = worldNames,
        actionNames = causeNames,
        itemNames = itemNames,
        blockNames = blockNames,
    ),
).map { it.text }
