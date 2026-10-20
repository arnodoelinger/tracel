package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.history.HistoryQuery
import com.tracel.api.result.Res
import com.tracel.api.world.LookupArea
import java.util.UUID

/**
 * What a flag is given: the [value] after `name:`, and where the sender stands, so `region:spawn` can mean the spawn
 * of the sender's own world.
 *
 * Both are `null` for the console.
 */
@Unstable
public data class FlagInput(public val value: String, public val world: UUID?, public val player: UUID?)

/** What a flag narrows a lookup or a rollback by. */
@Unstable
public fun interface QueryPart {
    /** Narrows what [query] reads. It only builds; nothing is read yet. */
    public fun narrow(query: HistoryQuery.Builder)

    public companion object {
        /** A part that only looks inside [area]. It replaces the area a `scope:` flag set, if any. */
        @JvmStatic
        public fun within(area: LookupArea): QueryPart = QueryPart { it.within(area) }
    }
}

/**
 * A `name:value` token an addon adds to `/tracel lookup`, `near` and `rollback`: `region:spawn`, `town:Atlantis`.
 *
 * Both calls are made where the command is typed, so keep them quick and never read the world.
 */
@Unstable
public interface QueryFlag {
    /** What comes before the colon: lower case letters, digits, `_` and `-`. */
    public val name: String

    /** What the tab key shows beside the flag's name. */
    public val description: String

    /**
     * Turns [input] into a part of the query, or says in [Res.Err] what is wrong with it;
     * the sender is told so.
     */
    public fun parse(input: FlagInput): Res<QueryPart, String>

    /**
     * What may follow `name:` for [input]`.value` typed so far; whole values,
     * not completions of the typed part.
     */
    public fun suggest(input: FlagInput): List<String>
}

/** What a [QueryFlag] is built from. */
@Unstable
public class FlagBuilder internal constructor() {
    private var parser: (FlagInput) -> Res<QueryPart, String> = { Res.Err("this flag takes no value") }
    private var suggester: (FlagInput) -> List<String> = { emptyList() }

    /** What the flag says in the tab list. */
    public var description: String = ""

    /** How a value becomes a part of the query. */
    public fun parse(parse: (FlagInput) -> Res<QueryPart, String>) {
        parser = parse
    }

    /** What the tab key offers for a value typed so far. */
    public fun suggest(suggest: (FlagInput) -> List<String>) {
        suggester = suggest
    }

    internal fun build(name: String): QueryFlag = object : QueryFlag {
        override val name: String = name
        override val description: String = this@FlagBuilder.description
        override fun parse(input: FlagInput): Res<QueryPart, String> = parser(input)
        override fun suggest(input: FlagInput): List<String> = suggester(input)
    }
}

/**
 * A flag called [name], built by [build]:
 *
 * ```
 * flag("region") {
 *     description = "A WorldGuard region"
 *     parse { input -> regions[input.value]?.let { Res.Ok(QueryPart.within(it.area)) } ?: Res.Err("no such region") }
 *     suggest { input -> regions.names.filter { it.startsWith(input.value) } }
 * }
 * ```
 */
@Unstable
public fun flag(name: String, build: FlagBuilder.() -> Unit): QueryFlag = FlagBuilder().apply(build).build(name)

/** Where flags are registered. */
@Unstable
public interface Flags {
    /** Registers [flag] until the addon is disabled. */
    public fun register(flag: QueryFlag)
}
