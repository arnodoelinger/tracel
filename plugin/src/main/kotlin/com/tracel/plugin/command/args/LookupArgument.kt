package com.tracel.plugin.command.args

import com.tracel.plugin.command.suggest.LookupSuggest
import com.tracel.plugin.command.suggest.SuggestLists

data class ParsedLookupArgs(
    val users: Set<String> = emptySet(),
    val excludedUsers: Set<String> = emptySet(),
    val item: String? = null,
    val actions: Set<String> = emptySet(),
    val since: Long? = null,
    val until: Long? = null,
    val scope: LookupScope? = null,
    val world: String? = null,
    val horizontalOnly: Boolean = false,
    val lot: Long? = null,
    val preview: Boolean = false,
    val structureOnly: Boolean = false,
    val materialOnly: Boolean = false,
    val strict: Boolean = false,
    val confirmed: Boolean = false,
    val trace: Boolean = false,
    val errors: List<String> = emptyList(),
)

internal data class LookupSuggestContext(
    val onlinePlayerNames: List<String>,
    val worldNames: List<String>,
    val causeNames: List<String>,
    val itemNames: List<String> = emptyList(),
    val blockNames: List<String> = emptyList(),
)

internal sealed interface LookupArgument {
    val prefix: String
    fun matches(token: String): Boolean
    fun apply(args: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs
    fun suggest(ctx: LookupSuggestContext): List<String> = emptyList()

    class Flag(
        override val prefix: String,
        private val set: (ParsedLookupArgs) -> ParsedLookupArgs,
    ) : LookupArgument {
        override fun matches(token: String) = token == prefix
        override fun apply(args: ParsedLookupArgs, token: String, nowMillis: Long) = set(args)
    }

    class Value(
        override val prefix: String,
        private val set: (ParsedLookupArgs, String) -> ParsedLookupArgs,
        private val values: (LookupSuggestContext) -> List<String> = { emptyList() },
    ) : LookupArgument {
        override fun matches(token: String) = token.startsWith(prefix)
        override fun apply(args: ParsedLookupArgs, token: String, nowMillis: Long) =
            set(args, token.removePrefix(prefix))

        override fun suggest(ctx: LookupSuggestContext) = values(ctx).map { "$prefix$it" }
    }

    class Multi(
        override val prefix: String,
        private val get: (ParsedLookupArgs) -> Set<String>,
        private val set: (ParsedLookupArgs, Set<String>) -> ParsedLookupArgs,
        private val names: (LookupSuggestContext) -> List<String> = { emptyList() },
    ) : LookupArgument {
        override fun matches(token: String) = token.startsWith(prefix)
        override fun apply(args: ParsedLookupArgs, token: String, nowMillis: Long) =
            set(args, get(args) + token.removePrefix(prefix).split(",").filter(String::isNotBlank))

        override fun suggest(ctx: LookupSuggestContext) = names(ctx).map { "$prefix$it" }
    }

    class Parsed<T>(
        override val prefix: String,
        private val parse: (String, Long) -> T?,
        private val set: (ParsedLookupArgs, T) -> ParsedLookupArgs,
        private val values: (LookupSuggestContext) -> List<String> = { emptyList() },
    ) : LookupArgument {
        override fun matches(token: String) = token.startsWith(prefix)
        override fun apply(args: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs {
            val value = parse(token.removePrefix(prefix), nowMillis)
            return if (value == null) {
                args.copy(errors = args.errors + "invalid ${prefix.trimEnd(':')}: $token")
            } else {
                set(args, value)
            }
        }

        override fun suggest(ctx: LookupSuggestContext) = values(ctx).map { "$prefix$it" }
    }
}

private val LOOKUP_ARGUMENTS: List<LookupArgument> = listOf(
    LookupArgument.Flag("#preview") { it.copy(preview = true) },
    LookupArgument.Flag("#blocks") { it.copy(structureOnly = true) },
    LookupArgument.Flag("#items") { it.copy(materialOnly = true) },
    LookupArgument.Flag("#explosion") { it.copy(actions = it.actions + "explosion") },
    LookupArgument.Flag("#strict") { it.copy(strict = true) },
    LookupArgument.Flag("#confirm") { it.copy(confirmed = true) },
    LookupArgument.Flag("#wide") { it.copy(horizontalOnly = true) },
    LookupArgument.Flag("#trace") { it.copy(trace = true) },

    LookupArgument.Multi("-user:", { it.excludedUsers }, { r, v -> r.copy(excludedUsers = v) }, { it.onlinePlayerNames }),
    LookupArgument.Multi("user:", { it.users }, { r, v -> r.copy(users = v) }, { it.onlinePlayerNames }),
    LookupArgument.Value("item:", { r, v -> r.copy(item = v) }, { it.itemNames }),
    LookupArgument.Value("block:", { r, v -> r.copy(item = v) }, { it.blockNames }),
    LookupArgument.Multi("action:", { it.actions }, { r, v -> r.copy(actions = v) }, { it.causeNames }),
    LookupArgument.Parsed("scope:", { v, _ -> ScopeArgument.parse(v) }, { r, v -> r.withScope(v) }, { ScopeArgument.suggestions(it.worldNames) }),
    LookupArgument.Parsed("before:", { v, now -> TimeArgument.parseDuration(v)?.let { now - it } }, { r, v -> r.copy(until = earlier(r.until, v)) }, { TimeArgument.durationSuggestions() }),
    LookupArgument.Parsed("after:", { v, now -> TimeArgument.parseDuration(v)?.let { now - it } }, { r, v -> r.copy(since = later(r.since, v)) }, { TimeArgument.durationSuggestions() }),
    LookupArgument.Parsed("time:", { v, now -> TimeArgument.parseExpr(v, now) }, { r, v -> r.within(v) }, { TimeArgument.timeSuggestions() }),
    LookupArgument.Parsed("lot:", { v, _ -> v.toLongOrNull() }, { r, v -> r.copy(lot = v) }),

    // Aliases
    LookupArgument.Multi("-u:", { it.excludedUsers }, { r, v -> r.copy(excludedUsers = v) }, { it.onlinePlayerNames }),
    LookupArgument.Multi("u:", { it.users }, { r, v -> r.copy(users = v) }, { it.onlinePlayerNames }),
    LookupArgument.Value("i:", { r, v -> r.copy(item = v) }, { it.itemNames }),
    LookupArgument.Value("b:", { r, v -> r.copy(item = v) }, { it.blockNames }),
    LookupArgument.Multi("a:", { it.actions }, { r, v -> r.copy(actions = v) }, { it.causeNames }),
    LookupArgument.Parsed("w:", { v, _ -> v.takeIf(String::isNotBlank) }, { r, v -> r.copy(world = v) }, { it.worldNames }),
    LookupArgument.Parsed("t:", { v, now -> TimeArgument.parseExpr(v, now) }, { r, v -> r.within(v) }, { TimeArgument.timeSuggestions() }),
    LookupArgument.Parsed("l:", { v, _ -> v.toLongOrNull() }, { r, v -> r.copy(lot = v) }),
)

fun parseLookupArgs(args: List<String>, nowMillis: Long): ParsedLookupArgs =
    args.fold(ParsedLookupArgs()) { result, token ->
        val argument = LOOKUP_ARGUMENTS.firstOrNull { it.matches(token) }
        argument?.apply(result, token, nowMillis)
            ?: result.copy(errors = result.errors + "unrecognized flag: $token")
    }

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

private fun ParsedLookupArgs.within(window: TimeExpr): ParsedLookupArgs =
    copy(since = later(since, window.since), until = earlier(until, window.until))

private fun later(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)

private fun earlier(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)
