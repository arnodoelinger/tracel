package com.tracel.plugin.listener.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toItemStacks
import org.bukkit.Location
import org.bukkit.World

/**
 * Spawns [quantity] of [itemKey] as real ground items at [location], under
 * [TracelServices.selfManagedSpawns].
 */
fun TracelServices.spawnAsRelease(itemKey: ItemKey, quantity: Long, world: World, location: Location): List<InventoryDelta> =
    itemKey.toItemStacks(quantity).map { stack ->
        val item = selfManagedSpawns.whileSpawning { world.dropItemNaturally(location, stack) }
        InventoryDelta(HolderId.ItemEntity(item.uniqueId), itemKey, stack.amount.toLong())
    }
