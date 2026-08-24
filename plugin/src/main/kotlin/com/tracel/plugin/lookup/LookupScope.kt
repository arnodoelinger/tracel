package com.tracel.plugin.lookup

private val CHUNK_RADIUS = Regex("""(\d+)c""")

sealed interface LookupScope {
    data class Blocks(val radius: Int) : LookupScope
    data class Chunks(val radius: Int) : LookupScope
    data object CurrentChunk : LookupScope
    data class World(val name: String) : LookupScope
}

/**
 * Parses a `scope:` value — a bare block radius, `Nc` chunk radius, the literal
 * `chunk`, or a world name.
 */
internal fun parseScope(value: String): LookupScope? = when {
    value.isBlank() -> null
    value == "chunk" -> LookupScope.CurrentChunk
    value.toIntOrNull() != null -> LookupScope.Blocks(value.toInt())
    CHUNK_RADIUS.matchEntire(value) != null -> LookupScope.Chunks(CHUNK_RADIUS.matchEntire(value)!!.groupValues[1].toInt())
    else -> LookupScope.World(value)
}
