package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.Observes
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import com.tracel.plugin.services.TracelServices
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerItemMendEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

/** Durability the item key does not carry, written down against the tool's lot. */
class WearListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onDamage(event: PlayerItemDamageEvent) = worn(event.player, event.item, event.damage)

    @Observes
    fun onMend(event: PlayerItemMendEvent) = worn(event.player, event.item, -event.repairAmount)

    private fun worn(player: Player, item: ItemStack, by: Int) {
        if (by == 0 || !player.isLedgeredHolder()) return
        val before = (item.itemMeta as? Damageable)?.damage ?: return
        val after = (before + by).coerceIn(0, item.type.maxDurability.toInt())
        if (after != before) material.worn(player, item, before, after)
    }
}
