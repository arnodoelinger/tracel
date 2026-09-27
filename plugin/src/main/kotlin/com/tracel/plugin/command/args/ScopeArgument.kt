package com.tracel.plugin.command.args

sealed interface LookupScope {
    data class Blocks(val radius: Int) : LookupScope
    data class Chunks(val radius: Int) : LookupScope
    data object CurrentChunk : LookupScope
}

internal sealed interface ScopeValue {
    data class Radius(val scope: LookupScope) : ScopeValue
    data class World(val name: String) : ScopeValue
}

object ScopeArgument {
    private val BLOCK_RADIUS = Regex("""(\d+)b""")
    private val CHUNK_RADIUS = Regex("""(\d+)c""")

    fun describe(scope: LookupScope): String = when (scope) {
        is LookupScope.Blocks -> "${scope.radius}b"
        is LookupScope.Chunks -> "${scope.radius}c"
        LookupScope.CurrentChunk -> "chunk"
    }

    internal fun parse(value: String): ScopeValue? = when {
        value.isBlank() -> null
        value == "chunk" -> ScopeValue.Radius(LookupScope.CurrentChunk)
        value.toIntOrNull() != null -> ScopeValue.Radius(LookupScope.Blocks(value.toInt()))
        else -> BLOCK_RADIUS.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
            ScopeValue.Radius(LookupScope.Blocks(it))
        } ?: CHUNK_RADIUS.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
            ScopeValue.Radius(LookupScope.Chunks(it))
        } ?: ScopeValue.World(value)
    }

    internal fun suggestions(worldNames: List<String>): List<String> =
        listOf("10b", "32b", "64b", "4c", "8c", "16c", "chunk") + worldNames
}

object ScopeLimits {
    const val MAX_BLOCK_RADIUS: Int = 1024
    const val MAX_CHUNK_RADIUS: Int = MAX_BLOCK_RADIUS shr 4

    fun LookupScope.isOversized(): Boolean = when (this) {
        is LookupScope.Blocks -> radius > MAX_BLOCK_RADIUS
        is LookupScope.Chunks -> radius > MAX_CHUNK_RADIUS
        LookupScope.CurrentChunk -> false
    }
}

internal fun ParsedLookupArgs.withScope(value: ScopeValue): ParsedLookupArgs = when (value) {
    is ScopeValue.Radius -> copy(scope = value.scope)
    is ScopeValue.World -> copy(world = value.name)
}
