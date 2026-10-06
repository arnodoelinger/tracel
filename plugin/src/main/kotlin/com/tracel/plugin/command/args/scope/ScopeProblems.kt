package com.tracel.plugin.command.args.scope

import com.tracel.plugin.command.args.action.ActionArgument
import com.tracel.plugin.command.args.action.ActionFilter
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.ScopeLimits.isOversized
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World

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
