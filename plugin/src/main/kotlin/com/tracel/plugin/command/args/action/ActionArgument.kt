package com.tracel.plugin.command.args.action

import com.tracel.model.cause.CauseKind
import com.tracel.model.event.EventKind
import com.tracel.model.world.ActionKind

/** Parses the `action:` / `a:` flag's alias / enum names into an [ActionFilter]. */
object ActionArgument {
    val NAMES: List<String> = ALIASES.flatMap { it.names }.distinct()

    /** Parse action. */
    fun parse(names: Set<String>): ActionFilter {
        if (names.isEmpty()) return ActionFilter()

        val causes = mutableSetOf<CauseKind>()
        val worldCauses = mutableSetOf<CauseKind>()
        val actions = mutableSetOf<ActionKind>()
        val unknown = mutableListOf<String>()
        val halves = mutableSetOf<Half>()
        val events = mutableSetOf<EventKind>()

        for (raw in names) {
            val alias = ALIAS_BY_NAME[raw.lowercase()]
            if (alias != null) {
                causes += alias.causes
                if (alias.half == Half.BOTH) worldCauses += alias.causes
                actions += alias.actions
                events += alias.events
                halves += alias.half
                continue
            }

            val cause = if (raw.equals("hopper", ignoreCase = true)) CauseKind.MACHINE else raw.toEnumOrNull<CauseKind>()
            val action = raw.toEnumOrNull<ActionKind>()
            when {
                action != null -> {
                    actions += action
                    halves += Half.STRUCTURE
                }

                cause != null -> {
                    causes += cause
                    worldCauses += cause
                    halves += Half.BOTH
                }

                else -> unknown += raw
            }
        }

        if (halves.isEmpty()) return ActionFilter(causes, worldCauses, actions, unknown)

        return ActionFilter(
            causes = causes,
            worldCauses = worldCauses,
            actions = actions,
            unknown = unknown,
            mixed = actions.isNotEmpty() && worldCauses.isNotEmpty(),
            structural = Half.STRUCTURE in halves || Half.BOTH in halves,
            material = Half.MATERIAL in halves || Half.BOTH in halves,
            events = events,
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        runCatching { enumValueOf<T>(uppercase()) }.getOrNull()
}
