package com.tracel.plugin.lookup

private val LOOKUP_FLAGS: List<LookupFlag> = listOf(
    ExactFlag("#count") { it.copy(count = true) },
    ExactFlag("#count-only") { it.copy(countOnly = true) },
    ExactFlag("#scope_horizontal_only") { it.copy(horizontalOnly = true) },
    SetFlag("-user:", { it.excludedUsers }, { r, v -> r.copy(excludedUsers = v) }, { it.onlinePlayerNames }),
    SetFlag("user:", { it.users }, { r, v -> r.copy(users = v) }, { it.onlinePlayerNames }),
    ValueFlag("item:") { r, v -> r.copy(item = v) },
    SetFlag("action:", { it.actions }, { r, v -> r.copy(actions = v) }, { it.causeNames }),
    ParsedFlag("scope:", { v, _ -> parseScope(v) }, { r, v -> r.copy(scope = v) }, { listOf("chunk") + it.worldNames }),
    ParsedFlag("limit:", { v, _ -> v.toIntOrNull() }, { r, v -> r.copy(limit = v) }),
    ParsedFlag("offset:", { v, _ -> v.toIntOrNull() }, { r, v -> r.copy(offset = v) }),
    ParsedFlag("before:", { v, now -> parseDurationMillis(v)?.let { now - it } }, { r, v -> r.copy(until = v) }),
    ParsedFlag("after:", { v, now -> parseDurationMillis(v)?.let { now - it } }, { r, v -> r.copy(since = v) }),
    ParsedFlag(
        "time:",
        { v, now -> parseTimeExpr(v, now) },
        { r, v -> r.copy(since = v.since, until = v.until) },
        { listOf("today", "yesterday") },
    ),
)

/** Parse lookup arguments. */
fun parseLookupArgs(args: List<String>, nowMillis: Long): ParsedLookupArgs =
    args.fold(ParsedLookupArgs()) { result, token ->
        val flag = LOOKUP_FLAGS.firstOrNull { it.matches(token) }
        flag?.apply(result, token, nowMillis) ?: result.copy(errors = result.errors + "unrecognized flag: $token")
    }

fun suggestLookupToken(
    partial: String,
    onlinePlayerNames: List<String>,
    worldNames: List<String>,
    causeNames: List<String>,
): List<String> {
    val ctx = LookupSuggestContext(onlinePlayerNames, worldNames, causeNames)
    val active = LOOKUP_FLAGS.firstOrNull { !it.exact && partial.startsWith(it.prefix) }
    val candidates = active?.suggest(ctx)?.takeIf { it.isNotEmpty() } ?: LOOKUP_FLAGS.map { it.prefix }
    return candidates.filter { it.startsWith(partial) }
}
