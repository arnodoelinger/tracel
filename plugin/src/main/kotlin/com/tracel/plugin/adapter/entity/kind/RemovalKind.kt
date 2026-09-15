package com.tracel.plugin.adapter.entity.kind

import com.tracel.annotations.Unstable
import org.bukkit.event.entity.EntityRemoveEvent

/** Whether this removal is a world-log row. */
enum class RemovalKind {
    Ignore,
    SceneryOnly,
    BlamedOnly,
    Record,

    ;

    /** Whether this kind of removal is a world-log row. */
    fun records(scenery: Boolean, blamed: Boolean): Boolean = when (this) {
        Ignore -> false
        SceneryOnly -> scenery
        BlamedOnly -> blamed
        Record -> true
    }
}

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
    EntityRemoveEvent.Cause.DISCARD,
        -> RemovalKind.Ignore
    EntityRemoveEvent.Cause.DESPAWN
        -> RemovalKind.SceneryOnly
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
