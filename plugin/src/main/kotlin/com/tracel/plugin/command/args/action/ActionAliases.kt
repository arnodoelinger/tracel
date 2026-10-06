package com.tracel.plugin.command.args.action

import com.tracel.model.cause.CauseKind
import com.tracel.model.event.EventKind
import com.tracel.model.world.ActionKind

internal enum class Half {
    STRUCTURE,
    MATERIAL,
    BOTH,
    EVENT,
}

internal data class ActionAlias(
    val names: Set<String>,
    val actions: Set<ActionKind> = emptySet(),
    val causes: Set<CauseKind> = emptySet(),
    val events: Set<EventKind> = emptySet(),
    val half: Half,
)

internal val ALIASES: List<ActionAlias> = listOf(
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
        causes = setOf(CauseKind.PLAYER_ACTION, CauseKind.MACHINE, CauseKind.BLOCK_BREAK, CauseKind.WEAR),
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

internal val ALIAS_BY_NAME: Map<String, ActionAlias> =
    ALIASES.flatMap { alias -> alias.names.map { it to alias } }.toMap()
