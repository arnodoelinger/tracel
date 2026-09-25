package com.tracel.plugin.adapter.entity.capability.cargo

import com.tracel.plugin.adapter.entity.special.ItemFrameCargo
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Every cargo surface, in the order vanilla actually mutates the entity. */
internal object CargoSurfaces {
    private val all: List<CargoSurface> = listOf(
        InventoryCargo,
        EquipmentAsCargo,
        MobEquipmentCargo,
        ItemFrameCargo,
        ChestedCargo,
    )

    /** Ticks to wait before the second push. One is not enough for mule chests and stand equipment. */
    private const val RESYNC_DELAY_TICKS = 2L

    /** Surfaces that own cargo on [entity]. Several may match the same hull. */
    fun matching(entity: Entity): List<CargoSurface> = all.filter { it.matches(entity) }

    /** Ledger-tracked stacks from every matching surface, clones, air skipped. */
    fun collect(entity: Entity): List<ItemStack> = buildList {
        for (surface in matching(entity)) {
            surface.collect(entity) { stack -> if (stack != null) add(stack) }
        }
    }

    /** Strips every matching surface. Skip one and the ledger delivers a second copy. */
    fun empty(entity: Entity) {
        for (surface in matching(entity)) surface.empty(entity)
    }

    /** Per-surface snapshots keyed by the surface that produced them. */
    fun save(entity: Entity): Map<CargoSurface, Any?> =
        matching(entity).associateWith { it.save(entity) }

    /**
     * Writes [saved] back in reverse of [all].
     *
     * A mule needs the chest before the inventory exists. Keys missing from
     * [saved] stay as they are — this is not a wipe.
     */
    fun restore(entity: Entity, saved: Map<CargoSurface, Any?>) {
        for (surface in matching(entity).asReversed()) {
            if (saved.containsKey(surface)) surface.restore(entity, saved[surface])
        }
    }

    /**
     * Pushes restored cargo to nearby players. Most surfaces no-op.
     *
     * Once now, once after [RESYNC_DELAY_TICKS]: the client often keeps the old
     * chest or stand gear until a later tick.
     */
    fun resync(entity: Entity, plugin: Plugin) {
        fun push() {
            for (surface in matching(entity)) surface.resync(entity, plugin)
        }
        push()
        entity.scheduler.runDelayed(plugin, {
            if (entity.isValid) push()
        }, {}, RESYNC_DELAY_TICKS)
    }
}
