package com.tracel.plugin.bukkit.entity

import com.tracel.annotations.Unstable
import com.tracel.model.world.entity.EntityExtras
import com.tracel.plugin.bukkit.entity.nbt.EntityEssence
import com.tracel.plugin.util.runtime.messageOrName
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Entity
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("EntityState")

/** Copies `Paper` capabilities from a deserialized ghost onto the live hull. */
internal fun applyShapeInPlace(entity: Entity, extras: EntityExtras.Opaque?) {
    extras ?: return
    val at = entity.location
    val nbt = EntityEssence.inflate(extras.bytes, null, at.x, at.y, at.z, at.yaw, at.pitch)
    val ghost = deserializeEntity(nbt, entity.world, preserveUUID = false) ?: return
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
    logger.log(Level.FINE, "Could not deserialize an entity into ${world.name} (${it.messageOrName()}).", it)
    null
}
