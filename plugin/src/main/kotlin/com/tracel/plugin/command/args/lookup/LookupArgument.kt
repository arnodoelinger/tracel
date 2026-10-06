package com.tracel.plugin.command.args.lookup

import com.tracel.plugin.i18n.tr

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
                args.copy(errors = args.errors + tr("common.invalid", "token" to token))
            } else {
                set(args, value)
            }
        }

        override fun suggest(ctx: LookupSuggestContext) = values(ctx).map { "$prefix$it" }
    }
}
