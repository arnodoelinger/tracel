package com.tracel.plugin.specifics.entity

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.kind.RemovalKind
import org.bukkit.event.entity.EntityRemoveEvent

/**
 * Maps `Paper`'s cause onto [RemovalKind].
 *
 * Unknown causes are [RemovalKind.Record] — better a row than a hole.
 */
@Unstable
fun EntityRemoveEvent.Cause.kind(): RemovalKind = when (this) {
    EntityRemoveEvent.Cause.UNLOAD,
    EntityRemoveEvent.Cause.PLAYER_QUIT,
    EntityRemoveEvent.Cause.PICKUP,
    EntityRemoveEvent.Cause.MERGE,
    EntityRemoveEvent.Cause.ENTER_BLOCK,
        -> RemovalKind.Ignore

    EntityRemoveEvent.Cause.DESPAWN
        -> RemovalKind.SceneryOnly

    EntityRemoveEvent.Cause.DISCARD,
    EntityRemoveEvent.Cause.TRANSFORMATION
        -> RemovalKind.BlamedOnly

    else -> RemovalKind.Record
}

/**
 * True when this removal should be a world-log row.
 *
 * Unload, quit, pickup, merge are not.
 */
fun shouldLogRemoval(cause: EntityRemoveEvent.Cause, scenery: Boolean, blamed: Boolean): Boolean =
    cause.kind().records(scenery, blamed)
