package com.tracel.plugin.command.args.scope

object ScopeArgument {
    private val BLOCK_RADIUS = Regex("""(\d+)b""")
    private val CHUNK_RADIUS = Regex("""(\d+)c""")

    fun describe(scope: LookupScope): String = when (scope) {
        is LookupScope.Blocks -> "${scope.radius}b"
        is LookupScope.Chunks -> "${scope.radius}c"
        LookupScope.CurrentChunk -> "chunk"
        LookupScope.CurrentBlock -> "block"
    }

    internal fun parse(value: String): ScopeValue? = when {
        value.isBlank() -> null
        value == "chunk" -> ScopeValue.Radius(LookupScope.CurrentChunk)
        value == "block" -> ScopeValue.Radius(LookupScope.CurrentBlock)
        value.toIntOrNull() != null -> ScopeValue.Radius(LookupScope.Blocks(value.toInt()))
        else -> BLOCK_RADIUS.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
            ScopeValue.Radius(LookupScope.Blocks(it))
        } ?: CHUNK_RADIUS.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
            ScopeValue.Radius(LookupScope.Chunks(it))
        } ?: ScopeValue.World(value)
    }

    internal fun suggestions(worldNames: List<String>): List<String> =
        listOf("4b", "8b", "16b", "32b", "64b", "128b", "block", "chunk") + worldNames
}
