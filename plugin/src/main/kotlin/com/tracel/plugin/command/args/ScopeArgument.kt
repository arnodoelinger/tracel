package com.tracel.plugin.command.args

import com.tracel.plugin.command.args.ScopeLimits.isOversized
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World

sealed interface LookupScope {
    data class Blocks(val radius: Int) : LookupScope
    data class Chunks(val radius: Int) : LookupScope
    data object CurrentChunk : LookupScope
    data object CurrentBlock : LookupScope
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

object ScopeLimits {
    const val MAX_BLOCK_RADIUS: Int = 1024
    const val MAX_CHUNK_RADIUS: Int = MAX_BLOCK_RADIUS shr 4

    @Volatile
    var rollbackMaxBlocks: Int? = MAX_BLOCK_RADIUS

    /** Whether it reaches past [maxBlocks] (or [maxBlocks] shifted to chunks); `null` limits nothing. */
    fun LookupScope.isOversized(maxBlocks: Int? = MAX_BLOCK_RADIUS): Boolean = when (this) {
        is LookupScope.Blocks -> maxBlocks != null && radius > maxBlocks
        is LookupScope.Chunks -> maxBlocks != null && radius > (maxBlocks shr 4)
        LookupScope.CurrentChunk, LookupScope.CurrentBlock -> false
    }
}

internal fun ParsedLookupArgs.withScope(value: ScopeValue): ParsedLookupArgs = when (value) {
    is ScopeValue.Radius -> copy(scope = value.scope)
    is ScopeValue.World -> copy(world = value.name)
}

/** Why [scope] cannot be searched from [center] in [world], or `null` when it can. */
internal fun scopeProblem(
    scope: LookupScope?,
    center: Location?,
    world: World?,
    maxBlocks: Int? = ScopeLimits.MAX_BLOCK_RADIUS,
): Component? = when {
    scope == null -> null
    center == null -> tr("common.reason.needs_player", "scope" to ScopeArgument.describe(scope))
    world != null && center.world?.uid != world.uid ->
        tr("common.reason.other_world", "scope" to ScopeArgument.describe(scope), "world" to world.name)

    scope.isOversized(maxBlocks) ->
        tr(
            "common.reason.too_large",
            "blocks" to maxBlocks,
            "chunks" to ((maxBlocks ?: 0) shr 4)
        )

    else -> null
}

/** Why [actions] cannot run, or `null` when they can. */
internal fun actionProblems(actions: ActionFilter, names: Set<String>): List<Component> = buildList {
    actions.unknown.forEach {
        add(tr("common.reason.unknown_action", "name" to it, "known" to ActionArgument.NAMES.joinToString(",")))
    }
    if (actions.mixed) add(tr("common.invalid", "token" to names.joinToString(",")))
}
