package com.tracel.plugin.bukkit.entity

import com.tracel.annotations.Unstable
import org.bukkit.entity.*
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ChestedHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * The chest strapped to the horse with a chest.
 *
 * @see [ChestedHorse]
 */
internal object ChestedCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is ChestedHorse

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        if ((entity as ChestedHorse).isCarryingChest) add(ItemStack(MOUNT_CHEST))
    }

    override fun empty(entity: Entity) {
        runCatching { (entity as ChestedHorse).isCarryingChest = false }
    }

    override fun save(entity: Entity): Any = (entity as ChestedHorse).isCarryingChest

    override fun restore(entity: Entity, saved: Any?) {
        val chested = saved as? Boolean ?: return
        runCatching { (entity as ChestedHorse).isCarryingChest = chested }
    }

    override fun resync(entity: Entity, plugin: Plugin) {
        entity.hideThenShow(plugin)
    }
}

/**
 * Armor-stand equipment.
 *
 * @see [ArmorStand]
 */
internal object EquipmentAsCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is ArmorStand

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        val equipment = (entity as ArmorStand).equipment
        for (slot in EquipmentSlot.entries) {
            addIfPresent(runCatching { equipment.getItem(slot) }.getOrNull(), add)
        }
    }

    override fun empty(entity: Entity) {
        val equipment = (entity as ArmorStand).equipment
        for (slot in EquipmentSlot.entries) {
            runCatching { equipment.setItem(slot, ItemStack.empty()) }
        }
    }

    override fun save(entity: Entity): Any {
        val equipment = (entity as ArmorStand).equipment
        return EquipmentSlot.entries.associateWith { equipment.getItem(it).clone() }
    }

    override fun restore(entity: Entity, saved: Any?) {
        @Suppress("UNCHECKED_CAST")
        val stand = saved as? Map<EquipmentSlot, ItemStack> ?: return
        val equipment = (entity as ArmorStand).equipment
        for ((slot, stack) in stand) runCatching { equipment.setItem(slot, stack) }
    }

    override fun resync(entity: Entity, plugin: Plugin) {
        val equipment = (entity as ArmorStand).equipment
        val viewers = runCatching { entity.trackedBy }.getOrDefault(emptySet())
        for (slot in EquipmentSlot.entries) {
            val stack = runCatching { equipment.getItem(slot) }.getOrNull() ?: continue
            for (player in viewers) {
                runCatching { player.sendEquipmentChange(entity, slot, stack) }
            }
        }
    }
}

/**
 * Whatever [InventoryHolder] currently holds.
 *
 * @see [InventoryHolder]
 */
internal object InventoryCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean =
        entity is InventoryHolder && !EquipmentAsCargo.matches(entity)

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        val holder = entity as InventoryHolder
        for (stack in holder.inventory.contents) addIfPresent(stack, add)
    }

    override fun empty(entity: Entity) {
        val inv = (entity as InventoryHolder).inventory
        for (i in 0 until inv.size) runCatching { inv.setItem(i, ItemStack.empty()) }
    }

    override fun save(entity: Entity): Any =
        (entity as InventoryHolder).inventory.contents.map { it?.clone() }.toTypedArray()

    override fun restore(entity: Entity, saved: Any?) {
        val contents = saved as? Array<*> ?: return
        val items = Array(contents.size) { contents[it] as? ItemStack }
        runCatching { (entity as InventoryHolder).inventory.contents = items }
    }
}

/** What a mob picked up or was handed. */
internal object MobEquipmentCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is Mob && entity !is ArmorStand && entity !is AbstractHorse

    fun owned(mob: Mob): List<EquipmentSlot> {
        val equipment = mob.equipment
        return EquipmentSlot.entries.filter { slot ->
            runCatching {
                mob.canUseEquipmentSlot(slot) &&
                        (equipment.getDropChance(slot) >= 1f || mob is CopperGolem && slot == EquipmentSlot.HAND)
            }.getOrDefault(false)
        }
    }

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        val mob = entity as Mob
        for (slot in owned(mob)) addIfPresent(runCatching { mob.equipment.getItem(slot) }.getOrNull(), add)
    }

    override fun empty(entity: Entity) {
        val mob = entity as Mob
        for (slot in owned(mob)) runCatching { mob.equipment.setItem(slot, ItemStack.empty()) }
    }

    override fun save(entity: Entity): Any {
        val mob = entity as Mob
        return owned(mob).associateWith { mob.equipment.getItem(it).clone() }
    }

    override fun restore(entity: Entity, saved: Any?) {
        @Suppress("UNCHECKED_CAST")
        val slots = saved as? Map<EquipmentSlot, ItemStack> ?: return
        val mob = entity as Mob
        for ((slot, stack) in slots) runCatching { mob.equipment.setItem(slot, stack) }
    }
}

/** Hide, then show the entity, so clients notice metadata the server already applied. */
@Unstable
internal fun Entity.hideThenShow(plugin: Plugin) {
    val viewers = viewersForCargo()
    for (player in viewers) runCatching { player.hideEntity(plugin, this) }
    scheduler.runDelayed(plugin, {
        if (!isValid) return@runDelayed
        for (player in viewersForCargo()) runCatching { player.showEntity(plugin, this) }
    }, {}, 1L)
}

/** Players who already have this entity. Empty means nobody to resync. */
internal fun Entity.viewersForCargo(): Collection<Player> =
    runCatching { trackedBy }.getOrDefault(emptySet())
