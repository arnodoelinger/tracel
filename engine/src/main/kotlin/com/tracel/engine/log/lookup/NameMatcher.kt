package com.tracel.engine.log.lookup

/**
 * Decides whether a name that a log holds is the one somebody asked for.
 *
 * How names are spelled, with a namespace or without, with their state or without, is the game's business and so
 * not the engine's: the log is handed a matcher that knows.
 */
public fun interface NameMatcher {
    /** Whether the [stored] name is the one [wanted] asks for. */
    public fun matches(stored: String, wanted: String): Boolean

    public companion object {
        public val Exact: NameMatcher = NameMatcher { stored, wanted -> stored.equals(wanted, ignoreCase = true) }
    }
}
