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
import org.bukkit.inventory.CraftingInventory
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
        if (type == InventoryType.ENDER_CHEST) HolderId.PlayerStash(owner.uniqueId)
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
        stack.addTo(totals)
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
    val totals = toMutableMap()
    cursor.addTo(totals)
    return totals
}

/** The crafting grid this player has open, a table or their own two by two. */
fun HumanEntity.openGrid(): CraftingInventory? =
    runCatching { openInventory.topInventory as? CraftingInventory }.getOrNull()

/**
 * All a player holds: pockets, cursor, the open grid and an open anvil's (or loom's, or trade's) inputs. Rebaselined without the grid, the next click
 * found four planks in it and minted them.
 */
fun Player.heldTotals(): Map<ItemKey, Long> {
    val totals = inventory.toItemTotals().withCursor(this).toMutableMap()
    openGrid()?.matrix?.forEach { it?.addTo(totals) }
    runCatching { openInventory.topInventory.transientInputs() }.getOrNull()
        ?.forEach { (key, qty) -> totals.merge(key, qty, Long::plus) }
    return totals
}

// Input slots of the menus whose contents go back to the player on close; the rest is a result preview
private val TRANSIENT_INPUTS: Map<InventoryType, IntRange> = mapOf(
    InventoryType.ANVIL to 0..1,
    InventoryType.GRINDSTONE to 0..1,
    InventoryType.STONECUTTER to 0..0,
    InventoryType.SMITHING to 0..2,
    InventoryType.LOOM to 0..2,
    InventoryType.CARTOGRAPHY to 0..1,
    InventoryType.ENCHANTING to 0..1,
    InventoryType.BEACON to 0..0,
    InventoryType.MERCHANT to 0..1,
)

/** The input slots of a menu [transientInputs] reads, or `null` for an inventory that is somebody's own. */
fun Inventory.transientInputSlots(): IntRange? = TRANSIENT_INPUTS[type]

/**
 * What sits in the input slots of a menu nobody owns but the player using it.
 *
 * @return `null` for an inventory that is somebody's own.
 */
fun Inventory.transientInputs(): Map<ItemKey, Long>? {
    val inputs = TRANSIENT_INPUTS[type] ?: return null
    val totals = mutableMapOf<ItemKey, Long>()
    for (slot in inputs) {
        if (slot >= size) break
        getItem(slot)?.takeIf { !it.type.isAir && it.amount > 0 }?.addTo(totals)
    }
    return totals
}
