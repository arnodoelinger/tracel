package com.tracel.plugin.convert

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

/**
 * `null` when the inventory is neither entity / player-backed nor placed anywhere in the world —
 * a virtual / crafting-result inventory, for instance. There is nothing meaningful to attribute
 * a flow to in that case, so the caller is expected to skip it rather than guess.
 */
fun Inventory.toHolderId(): HolderId? {
    return when (val owner = holder) {
        is Player -> HolderId.Player(owner.uniqueId)
        is Entity -> HolderId.Entity(owner.uniqueId)
        else -> {
            val loc = location ?: return null
            val world = loc.world ?: return null
            HolderId.Block(WorldId(world.uid), loc.blockX, loc.blockY, loc.blockZ)
        }
    }
}
