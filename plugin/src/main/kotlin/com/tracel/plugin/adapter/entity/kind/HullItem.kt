package com.tracel.plugin.adapter.entity.kind

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemKey
import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack

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
