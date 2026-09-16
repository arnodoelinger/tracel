package com.tracel.plugin.command.args

import com.tracel.annotations.CauseKind
import com.tracel.model.world.ActionKind

data class ActionFilter(
    val causes: Set<CauseKind> = emptySet(),
    val actions: Set<ActionKind> = emptySet(),
    val unknown: List<String> = emptyList(),
    val structural: Boolean = true,
    val material: Boolean = true,
)

private enum class Half {
    STRUCTURE,
    MATERIAL,
    BOTH
}

private data class ActionAlias(
    val names: Set<String>,
    val actions: Set<ActionKind> = emptySet(),
    val causes: Set<CauseKind> = emptySet(),
    val half: Half,
)

private val ALIASES: List<ActionAlias> = listOf(
    ActionAlias(
        names = setOf("block"),
        actions = setOf(ActionKind.BLOCK_PLACE, ActionKind.BLOCK_BREAK, ActionKind.BLOCK_CHANGE),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("+block", "place"),
        actions = setOf(ActionKind.BLOCK_PLACE),
        half = Half.STRUCTURE,
    ),
    ActionAlias(
        names = setOf("-block", "break"),
        actions = setOf(ActionKind.BLOCK_BREAK), half = Half.STRUCTURE),
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
        actions = setOf(ActionKind.ENTITY_REMOVE), half = Half.STRUCTURE),
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
        val actions = mutableSetOf<ActionKind>()
        val unknown = mutableListOf<String>()
        val halves = mutableSetOf<Half>()

        for (raw in names) {
            val alias = ALIAS_BY_NAME[raw.lowercase()]
            if (alias != null) {
                causes += alias.causes
                actions += alias.actions
                halves += alias.half
                continue
            }

            val cause = raw.toEnumOrNull<CauseKind>()
            val action = raw.toEnumOrNull<ActionKind>()
            when {
                cause != null -> {
                    causes += cause
                    halves += Half.BOTH
                }
                action != null -> {
                    actions += action
                    halves += Half.STRUCTURE
                }
                else -> unknown += raw
            }
        }

        if (halves.isEmpty()) return ActionFilter(causes, actions, unknown)

        return ActionFilter(
            causes = causes,
            actions = actions,
            unknown = unknown,
            structural = Half.STRUCTURE in halves || Half.BOTH in halves,
            material = Half.MATERIAL in halves || Half.BOTH in halves,
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        runCatching { enumValueOf<T>(uppercase()) }.getOrNull()
}
