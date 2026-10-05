package com.tracel.plugin.command.args

import com.tracel.model.transaction.CauseKind
import com.tracel.model.event.EventKind
import com.tracel.model.world.ActionKind

data class ActionFilter(
    val causes: Set<CauseKind> = emptySet(),
    val worldCauses: Set<CauseKind> = emptySet(),
    val actions: Set<ActionKind> = emptySet(),
    val unknown: List<String> = emptyList(),
    val mixed: Boolean = false,
    val structural: Boolean = true,
    val material: Boolean = true,
    val events: Set<EventKind> = emptySet(),
)

private enum class Half {
    STRUCTURE,
    MATERIAL,
    BOTH,
    EVENT,
}

private data class ActionAlias(
    val names: Set<String>,
    val actions: Set<ActionKind> = emptySet(),
    val causes: Set<CauseKind> = emptySet(),
    val events: Set<EventKind> = emptySet(),
    val half: Half,
)

private val ALIASES: List<ActionAlias> = listOf(
    ActionAlias(
        names = setOf("block"),
        actions = setOf(ActionKind.BLOCK_PLACE, ActionKind.BLOCK_BREAK, ActionKind.BLOCK_CHANGE, ActionKind.BLOCK_GROW),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("+block", "place"),
        actions = setOf(ActionKind.BLOCK_PLACE),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("-block", "break"),
        actions = setOf(ActionKind.BLOCK_BREAK), half = Half.STRUCTURE
    ),
    ActionAlias(
        names = setOf("sign"),
        actions = setOf(ActionKind.SIGN_EDIT),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("entity"),
        actions = setOf(ActionKind.ENTITY_SPAWN, ActionKind.ENTITY_REMOVE, ActionKind.ENTITY_CHANGE),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("+entity"),
        actions = setOf(ActionKind.ENTITY_SPAWN),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("-entity", "kill"),
        actions = setOf(ActionKind.ENTITY_REMOVE), half = Half.STRUCTURE
    ),
    ActionAlias(
        names = setOf("container", "item", "inventory"),
        causes = setOf(CauseKind.PLAYER_ACTION, CauseKind.HOPPER, CauseKind.BLOCK_BREAK, CauseKind.WEAR),
        half = Half.MATERIAL,
    ),
    ActionAlias(
        names = setOf("craft"),
        causes = setOf(CauseKind.CRAFT),
        half = Half.MATERIAL,
    ),
    ActionAlias(names = setOf("click"), actions = setOf(ActionKind.BLOCK_CLICK), half = Half.STRUCTURE),
    ActionAlias(names = setOf("grow"), actions = setOf(ActionKind.BLOCK_GROW), half = Half.STRUCTURE),
    ActionAlias(names = setOf("chat"), events = setOf(EventKind.CHAT), half = Half.EVENT),
    ActionAlias(names = setOf("command"), events = setOf(EventKind.COMMAND), half = Half.EVENT),
    ActionAlias(names = setOf("session"), events = setOf(EventKind.JOIN, EventKind.QUIT), half = Half.EVENT),
    ActionAlias(names = setOf("+session", "join"), events = setOf(EventKind.JOIN), half = Half.EVENT),
    ActionAlias(names = setOf("-session", "quit"), events = setOf(EventKind.QUIT), half = Half.EVENT),
    ActionAlias(names = setOf("death"), events = setOf(EventKind.DEATH), half = Half.EVENT),
    ActionAlias(names = setOf("shoot", "launch"), events = setOf(EventKind.SHOOT), half = Half.EVENT),
    ActionAlias(names = setOf("hit", "damage"), events = setOf(EventKind.HIT), half = Half.EVENT),
    ActionAlias(names = setOf("projectile"), causes = setOf(CauseKind.PROJECTILE), half = Half.BOTH),
    ActionAlias(
        names = setOf("explosion"),
        causes = setOf(CauseKind.EXPLOSION),
        half = Half.BOTH,
    ),
)

private val ALIAS_BY_NAME: Map<String, ActionAlias> =
    ALIASES.flatMap { alias -> alias.names.map { it to alias } }.toMap()

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

            val cause = raw.toEnumOrNull<CauseKind>()
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
