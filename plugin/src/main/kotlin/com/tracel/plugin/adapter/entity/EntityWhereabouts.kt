package com.tracel.plugin.adapter.entity

import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldId
import com.tracel.plugin.util.whereabouts.EntityWhereabouts
import com.tracel.plugin.util.whereabouts.GroundWhereabouts
import org.bukkit.entity.Entity

/** Remember the entity's current block position. */
fun EntityWhereabouts.remember(entity: Entity) {
    val loc = entity.location
    val world = loc.world ?: return
    remember(entity.uniqueId, HolderId.Block(WorldId(world.uid), loc.blockX, loc.blockY, loc.blockZ))
}

/**
 * Remember where [entity] is standing.
 *
 * Region thread and no storage hop.
 */
fun GroundWhereabouts.remember(entity: Entity) {
    val loc = entity.location
    val world = loc.world ?: return
    remember(entity.uniqueId, HolderId.Block(WorldId(world.uid), loc.blockX, loc.blockY, loc.blockZ))
}
