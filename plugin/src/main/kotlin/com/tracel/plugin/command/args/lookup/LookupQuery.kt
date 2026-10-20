package com.tracel.plugin.command.args.lookup

import com.tracel.api.actor.Actor
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.RecordKind
import com.tracel.api.item.ItemKind
import com.tracel.api.world.LookupArea
import com.tracel.model.event.EventKind
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.command.args.action.ActionFilter
import java.time.Instant
import java.util.UUID

/**
 * What a lookup command line reads, as the API asks for it: the blocks and entities,
 * the items, and the [events] it shows, each half only when the line wants it.
 */
internal fun ParsedLookupArgs.lookupQuery(
    users: Collection<UUID>,
    actions: ActionFilter,
    area: LookupArea?,
    events: Set<EventKind> = emptySet(),
): HistoryQuery = query(users, actions, area, natural, events)

/**
 * What a rollback command line takes back.
 *
 * A rollback never hides the world's own doings: if fire burned it, it comes back.
 */
internal fun ParsedLookupArgs.rollbackQuery(
    users: Collection<UUID>,
    actions: ActionFilter,
    area: LookupArea?,
): HistoryQuery = query(users, actions, area, natural = true, events = emptySet())

private fun ParsedLookupArgs.query(
    users: Collection<UUID>,
    actions: ActionFilter,
    area: LookupArea?,
    natural: Boolean,
    events: Set<EventKind>,
): HistoryQuery {
    val kinds = buildSet {
        if (actions.structural && !materialOnly) {
            add(RecordKind.BLOCK)
            add(RecordKind.ENTITY)
        }
        if (actions.material && !structureOnly) add(RecordKind.ITEM)
        if (events.isNotEmpty()) add(RecordKind.EVENT)
    }
    val query = HistoryQuery(
        area = area,
        kinds = kinds,
        events = events.mapTo(HashSet(), ApiConversions::event),
        since = since?.let(Instant::ofEpochMilli),
        until = until?.let(Instant::ofEpochMilli),
        by = users.mapTo(HashSet()) { Actor.Player(it) },
        causes = actions.causes.mapTo(HashSet(), ApiConversions::cause),
        blockCauses = actions.worldCauses.mapTo(HashSet(), ApiConversions::cause),
        actions = actions.actions.mapTo(HashSet(), ApiConversions::action),
        item = item?.let { ItemKind(it) },
        natural = natural,
    )
    return withParts(query)
}
