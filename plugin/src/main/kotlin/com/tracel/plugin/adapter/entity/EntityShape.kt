package com.tracel.plugin.adapter.entity

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.OpaqueBytes
import com.tracel.model.world.WorldId
import com.tracel.model.world.entity.*
import com.tracel.plugin.adapter.entity.link.leashedTo
import com.tracel.plugin.adapter.entity.link.ridingOn
import com.tracel.plugin.adapter.entity.special.FallingBlockAdapter
import com.tracel.plugin.util.log.Warnings
import io.papermc.paper.entity.EntitySerializationFlag
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

private val logger = Logger.getLogger("EntityShapes")

/** Block this entity occupies. Used as the world-log coordinate, not the exact pose. */
fun Entity.toBlockPos(): BlockPos =
    BlockPos(WorldId(world.uid), location.blockX, location.blockY, location.blockZ)

/**
 * Pose for the world log: type, location, facing, NBT without cargo.
 *
 * Cargo is the ledger's. Serializing with items still in the hull would duplicate them
 * into the world log and deliver a second copy on restore.
 */
fun Entity.toShape(): EntityShape = EntityShape(
    EntityTypeKey(type.key.asString()),
    location.x,
    location.y,
    location.z,
    location.yaw,
    location.pitch,
    entityExtras(this),
)

/**
 * Falling-block extras if this is sand in the air; otherwise opaque NBT plus
 * leash / vehicle UUIDs.
 */
private fun entityExtras(entity: Entity): EntityExtras? {
    FallingBlockAdapter.extrasOf(entity)?.let { return it }
    val leash = entity.leashedTo()
    val recorded = leash?.takeIf { it !is Player }?.uniqueId
    val seat = entity.ridingOn()?.uniqueId
    return riding(leashed(snapshotOf(entity, recorded != null, seat != null), recorded), seat)
}

@Unstable
@Suppress("DEPRECATION")
/**
 * NBT of the empty hull. Cargo is stripped first and put back in `finally`.
 *
 * If emptying fails, nothing is recorded: a pose with items in NBT is a dupe waiting to happen.
 * If the blob is larger than the world-log extras budget (minus leash / vehicle bytes), the
 * hull restores without detail rather than truncating.
 */
private fun snapshotOf(entity: Entity, leashed: Boolean, riding: Boolean): EntityExtras? = runCatching {
    val surfaces = entity.shapeCargoSurfaces()
    val cargo = surfaces.associateWith { it.save(entity) }
    try {
        for (surface in surfaces) surface.empty(entity)
        val left =
            buildList { for (surface in surfaces) surface.collect(entity) { if (it != null && !it.isEmpty) add(it) } }
        if (left.isNotEmpty()) {
            Warnings.once(logger, "cargo:${entity.type}") {
                "a ${entity.type} could not be emptied before being captured — its pose is not recorded, " +
                        "because recording it would carry its contents into the world log"
            }
            return@runCatching null
        }
        val nbt = Bukkit.getUnsafe().serializeEntity(entity, EntitySerializationFlag.FORCE)
        val links = (if (leashed) OpaqueBytes.LINK_BYTES else 0) + if (riding) OpaqueBytes.LINK_BYTES else 0
        val room = OpaqueBytes.MAX_BYTES - links
        if (nbt.size > room) {
            Warnings.once(logger, "huge:${entity.type}") {
                "a ${entity.type} serializes to ${nbt.size} bytes, past the $room a " +
                        "world-log record can hold — it will be restored without its detail"
            }
            return@runCatching null
        }
        EntityExtras.Opaque(nbt)
    } finally {
        entity.restoreCargoSurfaces(cargo)
    }
}.getOrElse {
    logger.log(Level.FINE, "entity ${entity.type} could not be captured in full", it)
    null
}
