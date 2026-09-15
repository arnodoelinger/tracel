package com.tracel.plugin.adapter.item

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import org.bukkit.Material
import org.bukkit.entity.ChestedHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

private val CHEST_KEY: ItemKey = ItemStack(Material.CHEST).toItemKey()

/**
 * Ledger account that owns this inventory.
 *
 * Player, ender chest, entity cargo, or the block at [Inventory.getLocation].
 * `null` if Bukkit has no holder and no location.
 */
@Unstable
fun Inventory.toHolderId(): HolderId? = when (val owner = holder) {
    is Player ->
        if (type == InventoryType.ENDER_CHEST) HolderId.EnderChest(owner.uniqueId)
        else HolderId.Player(owner.uniqueId)
    is Entity -> HolderId.Entity(owner.uniqueId)
    else -> {
        val loc = location ?: return null
        val world = loc.world ?: return null
        HolderId.Block(WorldId(world.uid), loc.blockX, loc.blockY, loc.blockZ)
    }
}

/**
 * How many of each item this inventory holds. Slots do not matter.
 *
 * Moving a stack from slot 3 to slot 7 is not a transfer. If the differ sees
 * it as one, the snapshot is wrong.
 */
fun Inventory.toItemTotals(): Map<ItemKey, Long> {
    val totals = mutableMapOf<ItemKey, Long>()
    for (stack in contents) {
        if (stack == null || stack.type.isAir) continue
        val key = stack.toItemKey()
        totals[key] = (totals[key] ?: 0L) + stack.amount
    }
    val worn = holderOrNull() as? ChestedHorse
    if (worn != null && runCatching { worn.isCarryingChest }.getOrDefault(false)) {
        totals[CHEST_KEY] = (totals[CHEST_KEY] ?: 0L) + 1L
    }
    return totals
}

// Folia: getHolder() on a chest in another region throws. We do not need the holder that badly
private fun Inventory.holderOrNull(): Any? = runCatching { holder }.getOrNull()

/**
 * Add whatever is on the mouse.
 *
 * `Bukkit` inventories do not include the cursor. Pick a stack up and put it
 * down without this, and the log shows a loss then a gain of the same item.
 */
fun Map<ItemKey, Long>.withCursor(player: HumanEntity): Map<ItemKey, Long> {
    val cursor = player.itemOnCursor
    if (cursor.type.isAir) return this
    val key = cursor.toItemKey()
    return this + (key to (this[key] ?: 0L) + cursor.amount)
}
