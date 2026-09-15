package com.tracel.plugin.adapter.entity

import com.tracel.annotations.Unstable
import com.tracel.model.world.entity.EntityExtras
import com.tracel.plugin.adapter.entity.capability.state.InPlaceStates
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Entity

private val logger = Logger.getLogger("EntityState")

/** Copies `Paper` capabilities from a deserialized ghost onto the live hull. */
internal fun applyShapeInPlace(entity: Entity, extras: EntityExtras.Opaque?) {
    extras ?: return
    val ghost = deserializeEntity(extras.nbt, entity.world, preserveUUID = false) ?: return
    if (ghost.isInWorld) {
        ghost.emptyCargo()
        runCatching { ghost.remove() }
        return
    }
    InPlaceStates.apply(entity, ghost)
}

/** Builds an entity from recorded NBT. */
@Unstable
internal fun deserializeEntity(nbt: ByteArray, world: World, preserveUUID: Boolean): Entity? = runCatching {
    @Suppress("DEPRECATION")
    Bukkit.getUnsafe().deserializeEntity(nbt, world, preserveUUID)
}.getOrElse {
    logger.log(Level.FINE, "could not deserialize an entity into ${world.name}", it)
    null
}
