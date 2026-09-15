package com.tracel.plugin.adapter.entity.kind

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemKey
import org.bukkit.Material
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
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
 * Whether this projectile is an item the ledger should book.
 *
 * A shot arrow a player can pick up, yes. A snow golem's snowball has no item and is not.
 */
fun Entity.shouldLogProjectile(): Boolean = when (this) {
    is AbstractArrow -> isReclaimable()
    is Projectile -> hullItemKey() != null
    else -> false
}
