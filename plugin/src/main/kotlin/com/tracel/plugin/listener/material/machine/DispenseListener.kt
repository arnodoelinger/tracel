package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.BlockRelease
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockDispenseEvent

/** Dispense listener. */
class DispenseListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onDispense(event: BlockDispenseEvent) {
        val item = event.item
        if (item.type.isAir || item.amount <= 0) return

        val block = event.block

        // CrafterCraftEvent already opened this eject's claim window
        if (block.type == Material.CRAFTER) return

        material.releasing(
            releases = listOf(
                BlockRelease(
                    holder = block.toHolderId(),
                    world = block.world, x = block.x, y = block.y, z = block.z,
                    contents = mapOf(item.toItemKey() to item.amount.toLong()),
                ),
            ),
            cause = CauseKind.WORLD,
            causedBy = null,
            at = block.toBlockPos(),
        )

        val armor = event as? BlockDispenseArmorEvent ?: return
        val target = armor.targetEntity
        if (target is Player) {
            material.scheduleReconcile(
                player = target,
                cause = CauseKind.WORLD
            )
            return
        }
        later(target.location) {
            val equipment = target.equipment ?: return@later
            material.reconcile(
                holder = HolderId.Entity(target.uniqueId),
                totals = listOf(
                    equipment.helmet,
                    equipment.chestplate,
                    equipment.leggings,
                    equipment.boots
                ).toItemTotals(),
                cause = CauseKind.WORLD,
                causedBy = null,
                at = target.toBlockPos(),
            )
        }
    }
}
