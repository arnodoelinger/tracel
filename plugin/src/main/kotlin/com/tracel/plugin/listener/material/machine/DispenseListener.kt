package com.tracel.plugin.listener.material.machine

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.annotations.Unstable
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.DispenseCell
import com.tracel.plugin.listener.support.drop.BlockRelease
import org.bukkit.Material
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockDispenseEvent

/** Dispense listener. */
@Unstable
class DispenseListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.HIGHEST) // A monitor cannot cancel
    fun holdWhileRestoring(event: BlockDispenseEvent) {
        if (services.frozen.isFrozen(event.block.toHolderId())) event.isCancelled = true
    }

    @Observes
    fun onDispense(event: BlockDispenseEvent) {
        val item = event.item
        if (item.type.isAir || item.amount <= 0) return

        val block = event.block

        // CrafterCraftEvent already opened this eject's claim window
        if (block.type == Material.CRAFTER) return

        val dispenser = block.toHolderId()
        val itemKey = item.toItemKey()
        val quantity = item.amount.toLong()

        val by = services.redstoneTriggers.recentPressNear(block.world, block.x, block.y, block.z)
        val cause = if (by is HolderId.Player) CauseKind.PLAYER_ACTION else CauseKind.WORLD

        val facing = (block.blockData as? Directional)?.facing
        if (by != null && facing != null && item.type.name.endsWith("_SPAWN_EGG")) DispenseCell.fired(
            block.getRelative(
                facing
            ), by
        )

        // Straight onto a body
        val armor = event as? BlockDispenseArmorEvent
        if (armor != null) {
            val target = armor.targetEntity
            val wearer = if (target is Player) HolderId.Player(target.uniqueId) else HolderId.Entity(target.uniqueId)
            material.adjust(dispenser, itemKey, -quantity)
            material.adjust(wearer, itemKey, quantity)
            material.positioned(
                cause = cause,
                causedBy = by,
                at = block.location,
                deltas = listOf(
                    InventoryDelta(dispenser, itemKey, -quantity),
                    InventoryDelta(wearer, itemKey, quantity)
                ),
                mintShortfallAt = dispenser,
            )
            return
        }

        material.releasing(
            releases = listOf(
                BlockRelease(
                    holder = dispenser,
                    world = block.world, x = block.x, y = block.y, z = block.z,
                    contents = mapOf(itemKey to quantity),
                ),
            ),
            cause = cause,
            causedBy = by,
            at = block.toBlockPos(),
        )
    }
}
