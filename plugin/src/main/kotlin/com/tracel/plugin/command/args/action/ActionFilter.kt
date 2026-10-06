package com.tracel.plugin.command.args.action

import com.tracel.model.cause.CauseKind
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
