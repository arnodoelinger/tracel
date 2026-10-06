package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.entity.cargoStacks
import com.tracel.plugin.adapter.entity.resyncCargoViewers
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.rollback.material.cargo.applyArmorStand
import com.tracel.plugin.adapter.rollback.material.cargo.applyItemFrame
import com.tracel.plugin.adapter.rollback.material.item.*
import com.tracel.plugin.adapter.rollback.material.spill.spillInRegion
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.specifics.entity.MOUNT_STORAGE_FROM
import org.bukkit.Bukkit
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ChestedHorse
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Mob
import org.bukkit.inventory.InventoryHolder

/**
 * Fill entity cargo.
 *
 * Must run on the entity's region. Frames / stands are one-slot; boats / mules are inventories.
 */
internal suspend fun MaterialRestorer.fillEntityCargo(
    holder: HolderId.Entity,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
    worn: WornStacks? = null,
): ApplyResult.Failed? {
    val entity = Bukkit.getEntity(holder.uuid) ?: return NO_HULL_FAILURE
    if (!Bukkit.isOwnedByCurrentRegion(entity)) return ELSEWHERE_FAILURE
    val moves = Moves()
    when (entity) {
        is ArmorStand -> {
            applyArmorStand(entity, deltas, forms, moves, worn)
            entity.resyncCargoViewers(services.plugin)
            services.differ.forget(holder)
        }

        is ItemFrame -> {
            applyItemFrame(entity, deltas, forms, moves, worn)
            entity.resyncCargoViewers(services.plugin)
            services.differ.forget(holder)
        }

        is InventoryHolder -> {
            val inventory = entity.inventory
            val horse = entity as? ChestedHorse
            val chestDelta = deltas[CHEST] ?: 0L

            // Worn chest is a flag
            val stored = inventory.contents.sumOf { stack ->
                if (stack != null && stack.toItemKey() == CHEST) stack.amount.toLong() else 0L
            }
            val attach = horse != null && !horse.isCarryingChest && chestDelta > 0L
            val detach = horse != null && horse.isCarryingChest && chestDelta < 0L && stored < -chestDelta

            if (attach) horse.isCarryingChest = true
            // After attach, getInventory() is a new wrapper
            val live = if (attach) entity.inventory else inventory
            val preferredSlots = asOf?.let { layoutFor(holder, it) }
                ?.groupBy { it.itemKey }
                .orEmpty()

            for ((itemKey, delta) in deltas) {
                // Worn chest is the flag
                val amount = when {
                    itemKey != CHEST -> delta
                    attach -> delta - 1L
                    detach -> delta + 1L
                    else -> delta
                }
                if (amount != 0L) {
                    applyDelta(itemKey, amount, forms[itemKey], moves, live, preferredSlots[itemKey].orEmpty(), worn)
                }
            }

            // Detach last: taking the chest off removes the slots, so whatever still sits in them goes on
            // the ground first, as vanilla drops it, instead of vanishing with them.
            if (detach) {
                for (slot in MOUNT_STORAGE_FROM until live.size) {
                    val left = live.getItem(slot) ?: continue
                    if (left.isEmpty || left.type.isAir) continue
                    moves.overflow += left.toItemKey() to left.clone()
                    live.setItem(slot, null)
                }
                horse.isCarryingChest = false
            }

            // Chest flag is metadata the client often misses
            if (attach || detach) entity.resyncCargoViewers(services.plugin)

            services.differ.rebaseline(holder, entity.cargoStacks().toItemTotals())
        }
        // What a mob holds or wears it picked up or was given: take it out of the slot, give it back into one
        is Mob -> {
            applyMobEquipment(entity, deltas, forms, moves)
            services.differ.forget(holder)
        }
        // Mint-through mob (egg via chicken): no inventory. Spill under the animal rather than vanish
        else -> {
            for ((itemKey, delta) in deltas) {
                if (delta <= 0L) {
                    moves.short(itemKey, -delta)
                    continue
                }
                val template = stackFor(itemKey, 1, forms[itemKey])
                if (template == null) {
                    moves.problem("${itemKey.material} is not an item this server can build")
                    continue
                }
                for (over in stacksOf(itemKey, delta, template)) moves.overflow += itemKey to over
            }
        }
    }
    spillInRegion(holder, moves, entity.world, entity.location, sink)
    return moves.failure()
}
