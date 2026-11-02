package com.tracel.plugin.bukkit.entity

import com.tracel.model.item.ItemKey
import com.tracel.plugin.bukkit.item.toItemKey
import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.inventory.ItemStack

/** Hull macher. */
internal fun interface HullMatch {
    /**
     * Whether this matcher owns [entity].
     *
     * Several matchers may accept the same hull.
     */
    fun matches(entity: Entity): Boolean
}

/** @return whether this removal is a world-log row. */
enum class RemovalKind {
    Ignore,
    SceneryOnly,
    BlamedOnly,
    Record;

    /** @return whether this kind of removal is a world-log row. */
    fun records(scenery: Boolean, blamed: Boolean): Boolean = when (this) {
        Ignore -> false
        SceneryOnly -> scenery
        BlamedOnly -> blamed
        Record -> true
    }
}

/**
 * Who is responsible for this spawn.
 *
 * @see [SpawnReason]
 */
enum class SpawnKind {
    /** Egg, command, breeding already in the world. Log now. */
    Immediate,

    /** [SpawnReason.DEFAULT]. Wait for [EntityPlaceEvent]. */
    Placed,

    /** Natural, portal, shoulder. Not ours. */
    World,
}

/**
 * What the world log does with this hull.
 *
 * @see [Entity]
 */
enum class WorldHull {
    /** Players and ground items. */
    Skip,

    /** Placed scenery. Logged whoever put it there. */
    Scenery,

    /** Mobs. Logged only when a person made them. */
    Creature,
}

/** How the world log treats this hull. Players and ground items are never a shape. */
fun Entity.worldHull(): WorldHull = when {
    this is Player || this is Item -> WorldHull.Skip
    Scenery.matches(this) -> WorldHull.Scenery
    this is LivingEntity -> WorldHull.Creature
    else -> WorldHull.Skip
}

/** Placed scenery: boats, hangings, TNT, displays. Wildlife is not. */
fun Entity.isScenery(): Boolean = worldHull() == WorldHull.Scenery

/**
 * Whether a spawn or removal of this hull can be a world-log row.
 *
 * False for players and dropped items — those are ledger material, not scenery.
 */
fun Entity.logsWorldShape(): Boolean = worldHull() != WorldHull.Skip

/** True if a player can pick this arrow up. */
fun AbstractArrow.isReclaimable(): Boolean = pickupStatus == AbstractArrow.PickupStatus.ALLOWED

/** The item this hull is if the registry has one. */
fun Entity.hullItemKey(): ItemKey? {
    val material = Material.matchMaterial(type.key.toString()) ?: return null
    if (!material.isItem) return null
    return ItemStack(material).toItemKey()
}

/**
 * The item this projectile really is.
 *
 * Keyed by the entity type, all of them came back plain and the decorated one read as burned.
 */
fun Entity.projectileItemKey(): ItemKey? {
    val stack = runCatching {
        when (this) {
            is AbstractArrow -> itemStack
            is ThrowableProjectile -> item
            is Firework -> item
            else -> null
        }
    }.getOrNull()
    if (stack != null && !stack.type.isAir) return stack.toItemKey()
    return hullItemKey()
}

/**
 * Whether this projectile is an item the ledger should book.
 *
 * A shot arrow a player can pick up, yes. A snow golem's snowball has no item and is not.
 */
fun Entity.shouldLogProjectile(): Boolean = when (this) {
    is AbstractArrow -> isReclaimable()
    is Projectile -> hullItemKey() != null
    else -> false
}
