package com.tracel.plugin.adapter.entity

import com.tracel.annotations.Unstable
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.model.world.entity.opaque
import com.tracel.model.world.entity.vehicle
import com.tracel.plugin.adapter.entity.special.FallingBlockAdapter
import com.tracel.plugin.adapter.entity.special.LeashKnotAdapter
import com.tracel.plugin.adapter.entity.special.ShoulderAdapter
import com.tracel.plugin.adapter.entity.special.ZombieConversion
import com.tracel.plugin.util.Warnings
import com.tracel.plugin.util.anchorPoint
import com.tracel.plugin.util.facingFromPose
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.util.BoundingBox
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Hanging
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Painting
import java.util.UUID
import java.util.logging.Logger

private val logger = Logger.getLogger("EntitySpawn")

internal const val DRIFTED = 1.0
internal const val DEAD_HEALTH = 0.0

/** Puts this shape back into [world]: reuse the live hull if it is still there, else spawn. */
fun EntityShape.spawnInto(
    world: World,
    expected: UUID? = null,
    keepCargo: Boolean = false,
    resurrect: Boolean = true,
): Entity? {
    val hull = putHullBack(world, expected, keepCargo, resurrect) ?: return null
    hull.applyLeash(extras.leashHolder)
    hull.applyVehicle(extras.vehicle)
    return hull
}

/**
 * Finds or creates the hull. Knots are reused per fence; a parrot is taken off a shoulder
 * before a new one is spawned. Identity is kept when `Paper` still has the UUID.
 */
@Unstable
private fun EntityShape.putHullBack(
    world: World,
    expected: UUID?,
    keepCargo: Boolean,
    resurrect: Boolean,
): Entity? {
    val loc = Location(world, x, y, z, yaw, pitch)
    world.getChunkAt(loc)

    val knot = if (LeashKnotAdapter.isType(type.value)) LeashKnotAdapter.at(world, loc) else null
    if (knot != null && expected != null && knot.uniqueId != expected) EntityAliases.remember(expected, knot.uniqueId)
    val self = knot
        ?: expected?.let { Bukkit.getEntity(it) }?.takeIf { it.isValid }
        ?: sittingAt(world, loc, expected)
    if (self != null && (expected == null || self.uniqueId == expected || self === knot)) {
        val drifted = runCatching {
            self.world != world || self.location.distanceSquared(loc) > DRIFTED * DRIFTED
        }.getOrDefault(false)
        if (drifted && self !is Hanging) {
            runCatching { self.teleportAsync(loc) }
        }
        if (!keepCargo) stripCargo(self)
        self.setRotation(yaw, pitch)
        if (self is Hanging) {
            runCatching { self.setFacingDirection(facingFromPose(yaw, pitch), true) }
        }
        applyShapeInPlace(self, extras.opaque)
        return self
    }
    if (!resurrect) return null
    if (expected != null) ShoulderAdapter.takeOff(expected)
    val falling = extras as? EntityExtras.Falling
    if (falling != null) return FallingBlockAdapter.spawnFromExtras(world, loc, falling, logger)

    val opaque = extras.opaque
    if (opaque != null) {
        val restored = deserializeEntity(opaque.nbt, world, preserveUUID = true)
            ?: deserializeEntity(opaque.nbt, world, preserveUUID = false)
        if (restored != null && place(restored, loc)) {
            if (!keepCargo) stripCargo(restored)
            revive(restored)
            ZombieConversion.halt(restored)
            if (expected != null && restored.uniqueId != expected) warnLostIdentity(type.value, expected)
            return restored
        }
        if (restored is FallingBlock) {
            FallingBlockAdapter.spawn(world, loc, restored.blockData, logger)?.let { return it }
        }
    }
    spawnByType(world, loc)?.let {
        if (expected != null) warnLostIdentity(type.value, expected)
        return it
    }
    if (FallingBlockAdapter.isType(type.value)) {
        Warnings.once(logger, "falling-nbt") { "a falling block had no nbt to restore from" }
        return null
    }
    return null
}

/** A recorded living hull with 0 health would spawn dead. Fill max health so it can stand. */
private fun revive(entity: Entity) {
    if (entity !is LivingEntity || entity.health > DEAD_HEALTH) return
    runCatching {
        entity.health = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: return@runCatching
    }
}

/** Once per type: we spawned a hull whose UUID is not the one the ledger has cargo for. */
private fun warnLostIdentity(type: String, expected: UUID) {
    Warnings.once(logger, "identity:$type") {
        "restored a $type without its recorded uuid (e.g. $expected) — its cargo cannot be delivered"
    }
}

///** True if this shape is a leash hitch. Vanilla keeps one per block and we reuse it. */
//internal fun EntityShape.isLeashKnot(): Boolean = LeashKnotAdapter.isType(type.value)

/**
 * Live entity already in the recorded block.
 *
 * UUID first, then the same type.
 */
internal fun EntityShape.sittingAt(
    world: World,
    loc: Location = Location(world, x, y, z),
    expected: UUID? = null,
): Entity? {
    val nearby = world.getNearbyEntities(BoundingBox.of(loc.block))
    if (expected != null) return nearby.firstOrNull { it.uniqueId == expected }
    val want = type.value
    return nearby.firstOrNull { it.type.key.asString() == want }
}

/** Spawns [entity] at [loc], or at the painting / frame anchor if it is hanging. */
private fun place(entity: Entity, loc: Location): Boolean {
    val at = if (entity is Hanging) anchorOf(entity, loc) else loc
    val ok = entity.isInWorld || entity.spawnAt(at)
    if (!ok) return false
    runCatching { entity.setRotation(loc.yaw, loc.pitch) }
    if (entity is Hanging) {
        runCatching { entity.setFacingDirection(facingFromPose(loc.yaw, loc.pitch), true) }
    }
    return entity.isInWorld
}

/** Block-center spawn point for a hanging, shifted for even-width paintings. */
private fun anchorOf(entity: Entity, loc: Location): Location {
    val art = (entity as? Painting)?.art
    val wide = runCatching { art?.blockWidth }.getOrNull() ?: 1
    val tall = runCatching { art?.blockHeight }.getOrNull() ?: 1
    val (x, y, z) = anchorPoint(loc.x, loc.y, loc.z, facingFromPose(loc.yaw, loc.pitch), wide, tall)
    return Location(loc.world, x, y, z, loc.yaw, loc.pitch)
}

/**
 * Spawn from the registry type. Hangings get facing from pose; no per-mob class list.
 */
private fun EntityShape.spawnByType(world: World, loc: Location): Entity? {
    val key = NamespacedKey.fromString(type.value) ?: return null
    val entityType = Registry.ENTITY_TYPE.get(key) ?: return null
    val cls = entityType.entityClass ?: return null
    return runCatching {
        world.spawn(loc, cls) { entity ->
            if (entity is Hanging) {
                runCatching { entity.setFacingDirection(facingFromPose(yaw, pitch), true) }
            }
        }
    }.getOrElse {
        Warnings.once(logger, "spawn:$type") { "could not restore a $type: ${it.message}" }
        null
    }
}

