package com.tracel.plugin.rollback.material.item

import com.tracel.model.item.ItemKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.inventory.ItemStack

/** Temporarily stores worn `ItemStacks` for offline players until they log in. */
internal object PendingWorn {
    private val byPlayer = ConcurrentHashMap<UUID, WornStacks>()

    fun keep(player: UUID, itemKey: ItemKey, stack: ItemStack) {
        byPlayer.computeIfAbsent(player) { WornStacks() }.took(itemKey, stack)
    }

    fun take(player: UUID): WornStacks? = byPlayer.remove(player)
}
