package com.tracel.engine.world

import com.tracel.model.holder.HolderId

/**
 * Remembers where a dropped item last touched the ground.
 *
 * Item transactions only know the item UUID. When the item despawns, this keeps its final position so a rollback can
 * drop it back in the right place.
 */
public interface GroundPositions {
    /** Saves the final ground position of multiple dropped items. */
    public suspend fun rememberAll(positions: Map<HolderId.ItemEntity, HolderId.Block>)

    /** Returns where [item] was last seen on the ground. */
    public suspend fun find(item: HolderId.ItemEntity): HolderId.Block?
}
