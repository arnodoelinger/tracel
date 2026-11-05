package com.tracel.engine.world

import java.util.*

/** Entities that carry booked material and are still somewhere in the world. */
public interface LiveEntities {
    /** Every entity on disk. Read once at start, before anything writes. */
    public fun loadAll(): Set<UUID>

    /**
     * Writes every change in [changes] as one commit: `true` keeps the entity,
     * `false` forgets it.
     */
    public suspend fun writeAll(changes: Map<UUID, Boolean>)
}
