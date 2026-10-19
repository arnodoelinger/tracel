package com.tracel.api.access

import com.tracel.api.Unstable

/**
 * What a plugin may do beyond reading. Every plugin holds every grant until the admin narrows it in `config.toml`:
 *
 * Example:
 * ```
 * [api]
 * grants = { Arena = ["attribute"], Shop = [] }
 * ```
 *
 * There `Arena` may only write, and `Shop` may only read. A plugin cannot widen its own list.
 */
@Unstable
public enum class Grant(public val configName: String) {
    /** Roll back, preview and undo, and borrow an area while doing it. */
    ROLLBACK("rollback"),

    /** Change blocks, give items and record events, in a player's name or the plugin's own. */
    ATTRIBUTE("attribute"),

    /** Change the world without it being logged at all. History gets a hole; a later rollback will not see it. */
    QUIET("quiet");

    public companion object {
        @JvmStatic
        public fun named(name: String): Grant? = entries.firstOrNull { it.configName.equals(name.trim(), true) }
    }
}
