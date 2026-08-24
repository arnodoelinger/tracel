package com.tracel.plugin.lookup

internal data class LookupSuggestContext(
    val onlinePlayerNames: List<String>,
    val worldNames: List<String>,
    val causeNames: List<String>,
)

internal interface LookupFlag {
    val prefix: String
    val exact: Boolean get() = false
    fun matches(token: String): Boolean = if (exact) token == prefix else token.startsWith(prefix)
    fun apply(result: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs
    fun suggest(ctx: LookupSuggestContext): List<String> = emptyList()
}

internal class ExactFlag(override val prefix: String, private val set: (ParsedLookupArgs) -> ParsedLookupArgs) : LookupFlag {
    override val exact = true
    override fun apply(result: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs = set(result)
}

internal class ValueFlag(override val prefix: String, private val set: (ParsedLookupArgs, String) -> ParsedLookupArgs) : LookupFlag {
    override fun apply(result: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs =
        set(result, token.removePrefix(prefix))
}

internal class SetFlag(
    override val prefix: String,
    private val get: (ParsedLookupArgs) -> Set<String>,
    private val set: (ParsedLookupArgs, Set<String>) -> ParsedLookupArgs,
    private val names: (LookupSuggestContext) -> List<String> = { emptyList() },
) : LookupFlag {
    override fun apply(result: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs =
        set(result, get(result) + token.removePrefix(prefix).split(","))

    override fun suggest(ctx: LookupSuggestContext): List<String> = names(ctx).map { "$prefix$it" }
}

internal class ParsedFlag<T>(
    override val prefix: String,
    private val parse: (String, Long) -> T?,
    private val set: (ParsedLookupArgs, T) -> ParsedLookupArgs,
    private val values: (LookupSuggestContext) -> List<String> = { emptyList() },
) : LookupFlag {
    override fun apply(result: ParsedLookupArgs, token: String, nowMillis: Long): ParsedLookupArgs {
        val value = parse(token.removePrefix(prefix), nowMillis)
        return if (value == null) {
            result.copy(errors = result.errors + "invalid ${prefix.trimEnd(':')}: $token")
        } else {
            set(result, value)
        }
    }

    override fun suggest(ctx: LookupSuggestContext): List<String> = values(ctx).map { "$prefix$it" }
}
